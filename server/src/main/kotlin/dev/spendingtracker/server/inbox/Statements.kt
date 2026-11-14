package dev.spendingtracker.server.inbox

import dev.spendingtracker.server.apiJson
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/** A statement read into rows. [notBooked] rows hadn't settled (pending, declined) and were left out. */
data class Statement(val format: String, val rows: List<ParsedRow>, val notBooked: Int)

class StatementException(message: String) : Exception(message)

/**
 * Reads a statement: GoCardless transactions JSON, or one of the CSV exports below, recognised by its header.
 * A CSV row without the bank's own id gets "csv:" and a hash of its fields, so uploading an overlapping statement
 * again matches the rows already there.
 */
fun readStatement(content: String, shape: FeedShape): Statement {
    val text = content.removePrefix("﻿")
    if (text.trimStart().startsWith("{") || text.trimStart().startsWith("[")) {
        val json = runCatching { apiJson.parseToJsonElement(text) }.getOrElse { throw StatementException("This isn't valid JSON") }
        val transactions = runCatching { goCardlessTransactions(json) }.getOrElse { throw StatementException("No GoCardless transactions in this JSON") }
        return Statement("GoCardless JSON", transactions.mapNotNull { parseGoCardless(it, shape) }, 0)
    }
    val lines = text.lines()
    for (format in CSV_FORMATS) {
        val headerIndex = lines.indexOfFirst { line -> format.marker.all { line.contains(it) } }
        if (headerIndex < 0) continue
        val records = readCsv(lines.drop(headerIndex).joinToString("\n"), format.delimiter)
        val header = records.first().map { it.trim() }
        val missing = format.required.filter { it !in header }
        if (missing.isNotEmpty()) throw StatementException("${format.name}: the statement has no ${missing.joinToString()} column")
        val seen = mutableMapOf<String, Int>()
        var notBooked = 0
        val rows = records.drop(1)
            .filter { record -> record.size > 1 && record.any { it.isNotBlank() } }
            .flatMap { record ->
                val fields = header.zip(record.map { it.trim() }).toMap()
                val raw = JsonObject(fields.filterValues { it.isNotEmpty() }.mapValues { JsonPrimitive(it.value) })
                val hash = sha(header.joinToString("\u0001") { fields[it].orEmpty() })
                val occurrence = seen.merge(hash, 1, Int::plus)!!
                val fallbackId = "csv:${hash.take(20)}${if (occurrence > 1) ":$occurrence" else ""}"
                val csv = Csv(fields)
                val row = runCatching { format.read(csv, raw, fallbackId) }.getOrElse { e ->
                    throw StatementException("${format.name}, line ${records.indexOf(record) + headerIndex + 1}: ${e.message}")
                }
                if (row == null) {
                    notBooked++
                    emptyList()
                } else {
                    listOf(row) + runCatching { format.extra(csv, raw, fallbackId, row) }.getOrElse { e ->
                        throw StatementException("${format.name}, line ${records.indexOf(record) + headerIndex + 1}: ${e.message}")
                    }
                }
            }
        return Statement(format.name, rows, notBooked)
    }
    throw StatementException("This doesn't look like a statement this app can read: Revolut, ČSOB, KB or Raiffeisenbank CSV, or GoCardless JSON")
}

private fun sha(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

/** One CSV record by column name. */
private class Csv(private val fields: Map<String, String>) {
    operator fun get(column: String): String = fields[column].orEmpty()

    fun amount(column: String) = parseAmount(get(column)) ?: throw IllegalArgumentException("\"${get(column)}\" in $column isn't an amount")

    fun amountOrNull(column: String) = get(column).takeIf { it.isNotEmpty() }?.let { parseAmount(it) }

    fun date(column: String) = parseDate(get(column)) ?: throw IllegalArgumentException("\"${get(column)}\" in $column isn't a date")

    fun dateOrNull(column: String) = parseDate(get(column))
}

/** "2.8.2026", "02.08.2026 20:31", "2. 8. 2026" or "2026-08-02 09:24:39" → the date. */
internal fun parseDate(text: String): LocalDate? {
    val value = text.trim()
    Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(value)?.let { m ->
        val (y, mo, d) = m.destructured
        return runCatching { LocalDate(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull()
    }
    val m = Regex("^(\\d{1,2})\\s*\\.\\s*(\\d{1,2})\\s*\\.\\s*(\\d{4})").find(value) ?: return null
    val (d, mo, y) = m.destructured
    return runCatching { LocalDate(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull()
}

/** RFC 4180-ish: quoted fields with "" for a quote, newlines inside quotes. */
internal fun readCsv(text: String, delimiter: Char): List<List<String>> {
    val records = mutableListOf<List<String>>()
    var record = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
            c == '"' -> quoted = !quoted
            !quoted && c == delimiter -> { record.add(field.toString()); field.clear() }
            !quoted && (c == '\n' || c == '\r') -> {
                if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                record.add(field.toString()); field.clear()
                records.add(record); record = mutableListOf()
            }
            else -> field.append(c)
        }
        i++
    }
    if (field.isNotEmpty() || record.isNotEmpty()) {
        record.add(field.toString())
        records.add(record)
    }
    return records
}

private class CsvFormat(
    val name: String,
    /** Every one of these appears in the header line, and only there. */
    val marker: List<String>,
    val delimiter: Char,
    val required: List<String>,
    val extra: (row: Csv, raw: JsonObject, fallbackId: String, main: ParsedRow) -> List<ParsedRow> = { _, _, _, _ -> emptyList() },
    /** Null for a row that hasn't settled. */
    val read: (row: Csv, raw: JsonObject, fallbackId: String) -> ParsedRow?,
)

private fun counterparty(account: String): Pair<String?, String> {
    val parsed = CzechAccount.parse(account)
    return (parsed?.iban ?: ibanOf(account)) to (parsed?.toString() ?: account)
}

private val REVOLUT_CODES = mapOf("Topup" to "TOPUP", "Deposit" to "TOPUP")

private val CSV_FORMATS = listOf(
    CsvFormat(
        name = "Revolut CSV",
        marker = listOf("Type", "Started Date", "Completed Date", "Description"),
        delimiter = ',',
        required = listOf("Type", "Started Date", "Completed Date", "Description", "Amount", "Currency", "State"),
        read = { row, raw, fallbackId ->
            if (row["State"] != "COMPLETED") return@CsvFormat null
            val type = row["Type"]
            val code = REVOLUT_CODES[type] ?: type.uppercase().replace(' ', '_')
            val description = row["Description"]
            val currency = row["Currency"].uppercase()
            val started = row.date("Started Date")
            val booked = row.dateOrNull("Completed Date") ?: started
            val person = code == "TRANSFER" || code == "REV_PAYMENT"
            ParsedRow(
                externalId = fallbackId,
                bookingDate = booked,
                valueDate = null,
                date = started,
                amountMinor = toMinor(row.amount("Amount"), currency),
                currency = currency,
                counterpartyName = if (person) description.replace(Regex("^(To|From|Payment from) "), "") else "",
                counterpartyIban = null,
                counterpartyAccount = "",
                text = description.replace(Regex("^(To|From|Payment from) "), ""),
                message = description,
                code = code,
                raw = raw,
            )
        },
        extra = { row, raw, fallbackId, main ->
            val fee = row.amountOrNull("Fee")?.let { toMinor(it, main.currency) } ?: 0L
            if (fee == 0L) emptyList() else listOf(
                ParsedRow(
                    externalId = "$fallbackId:fee",
                    bookingDate = main.bookingDate,
                    valueDate = null,
                    date = main.date,
                    amountMinor = -kotlin.math.abs(fee),
                    currency = main.currency,
                    counterpartyName = "Revolut",
                    counterpartyIban = null,
                    counterpartyAccount = "",
                    text = if (main.text.isNotEmpty()) "Revolut fee: ${main.text}" else "Revolut fee",
                    message = "Fee for ${main.message}".trim(),
                    code = "FEE",
                    raw = raw,
                ),
            )
        },
    ),
    CsvFormat(
        name = "ČSOB CSV",
        marker = listOf("datum zaúčtování", "číslo protiúčtu"),
        delimiter = ';',
        required = listOf("datum zaúčtování", "částka", "měna", "číslo protiúčtu", "kód banky protiúčtu", "jméno protistrany", "označení operace", "zpráva"),
    ) { row, raw, fallbackId ->
        val currency = row["měna"].uppercase().ifEmpty { "CZK" }
        val booked = row.date("datum zaúčtování")
        val account = listOf(row["číslo protiúčtu"], row["kód banky protiúčtu"]).filter { it.isNotEmpty() }.joinToString("/")
        val (iban, display) = counterparty(account)
        val name = squash(row["jméno protistrany"])
        val message = squash(row["zpráva"].ifEmpty { row["vlastní poznámka"] })
        val card = parseCsobCard(message)
        ParsedRow(
            externalId = row["ID transakce"].ifEmpty { fallbackId },
            bookingDate = booked,
            valueDate = null,
            date = card?.first ?: booked,
            amountMinor = toMinor(row.amount("částka"), currency),
            currency = currency,
            counterpartyName = if (card != null) "" else name,
            counterpartyIban = iban,
            counterpartyAccount = display,
            text = card?.second ?: name.ifEmpty { message }.ifEmpty { row["označení operace"] },
            message = message,
            code = "",
            raw = raw,
        )
    },
    CsvFormat(
        name = "KB CSV",
        marker = listOf("Datum zauctovani", "Protistrana"),
        delimiter = ';',
        required = listOf("Datum zauctovani", "Protistrana", "Nazev protiuctu", "Castka"),
    ) { row, raw, fallbackId ->
        val currency = row["Mena"].uppercase().ifEmpty { "CZK" }
        val booked = row.date("Datum zauctovani")
        val (iban, display) = counterparty(row["Protistrana"])
        val name = squash(row["Nazev protiuctu"])
        val message = squash(row["Zprava pro prijemce"].ifEmpty { row["Popis pro me"] })
        ParsedRow(
            externalId = row["Identifikace transakce"].ifEmpty { fallbackId },
            bookingDate = booked,
            valueDate = null,
            date = row.dateOrNull("Datum provedeni") ?: booked,
            amountMinor = toMinor(row.amount("Castka"), currency),
            currency = currency,
            counterpartyName = name,
            counterpartyIban = iban,
            counterpartyAccount = display,
            text = name.ifEmpty { message }.ifEmpty { display },
            message = message,
            code = "",
            raw = raw,
        )
    },
    CsvFormat(
        name = "Raiffeisenbank credit card CSV",
        marker = listOf("Číslo kreditní karty", "Datum transakce"),
        delimiter = ';',
        required = listOf("Datum transakce", "Datum zúčtování", "Typ transakce", "Zaúčtovaná částka", "Popis/Místo transakce"),
    ) { row, raw, fallbackId ->
        val currency = row["Měna zaúčtování"].uppercase().ifEmpty { "CZK" }
        val booked = row.dateOrNull("Datum zúčtování") ?: row.date("Datum transakce")
        val place = squash(row["Popis/Místo transakce"])
        ParsedRow(
            externalId = fallbackId,
            bookingDate = booked,
            valueDate = null,
            date = row.dateOrNull("Datum transakce") ?: booked,
            amountMinor = toMinor(row.amount("Zaúčtovaná částka"), currency),
            currency = currency,
            counterpartyName = "",
            counterpartyIban = null,
            counterpartyAccount = "",
            text = squash(row["Název Obchodníka"]).ifEmpty { place }.ifEmpty { row["Typ transakce"] },
            message = listOf(row["Typ transakce"], place).filter { it.isNotBlank() }.joinToString(" · "),
            code = "",
            raw = raw,
        )
    },
    CsvFormat(
        name = "Raiffeisenbank CSV",
        marker = listOf("Datum provedení", "Zaúčtovaná částka"),
        delimiter = ';',
        required = listOf("Datum provedení", "Datum zaúčtování", "Zaúčtovaná částka", "Číslo protiúčtu", "Název protiúčtu", "Zpráva"),
    ) { row, raw, fallbackId ->
        val currency = row["Měna účtu"].uppercase().ifEmpty { "CZK" }
        val booked = row.dateOrNull("Datum zaúčtování") ?: row.date("Datum provedení")
        val (iban, display) = counterparty(row["Číslo protiúčtu"])
        val name = squash(row["Název protiúčtu"])
        val message = squash(row["Zpráva"].ifEmpty { row["Poznámka"] })
        ParsedRow(
            externalId = row["Id transakce"].ifEmpty { fallbackId },
            bookingDate = booked,
            valueDate = null,
            date = row.dateOrNull("Datum provedení") ?: booked,
            amountMinor = toMinor(row.amount("Zaúčtovaná částka"), currency),
            currency = currency,
            counterpartyName = name,
            counterpartyIban = iban,
            counterpartyAccount = display,
            text = squash(row["Název obchodníka"]).ifEmpty { name }.ifEmpty { message }.ifEmpty { display },
            message = listOf(message, squash(row["Poznámka"])).filter { it.isNotEmpty() }.distinct().joinToString(" · "),
            code = "",
            raw = raw,
        )
    },
)
