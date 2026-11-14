package dev.spendingtracker.server.inbox

import dev.spendingtracker.shared.api.BankInstitution
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** "Částka: 40 CZK 13.09.2026Místo: NYX*brothersczsroPrague": ČSOB's card payments carry the real date and place in the message. */
private val CSOB_CARD = Regex("Částka:\\s*([\\d\\s.,]+?)\\s*([A-Z]{3})\\s*(\\d{2})\\.(\\d{2})\\.(\\d{4})\\s*Místo:\\s*(.*)$", RegexOption.DOT_MATCHES_ALL)

/** The date and place of a ČSOB card payment's message; null for any other message. */
fun parseCsobCard(message: String): Pair<LocalDate, String>? = CSOB_CARD.find(message)?.let { card ->
    val (_, _, d, m, y, place) = card.destructured
    runCatching { LocalDate(y.toInt(), m.toInt(), d.toInt()) }.getOrNull()?.let { it to squash(place) }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** The transactions of a GoCardless response ({"transactions": {"booked": […]}}), or a bare list of them. */
fun goCardlessTransactions(json: JsonElement): List<JsonObject> = when (json) {
    is JsonArray -> json.map { it.jsonObject }
    is JsonObject -> json.obj("transactions")?.get("booked")?.jsonArray?.map { it.jsonObject }
        ?: json["booked"]?.jsonArray?.map { it.jsonObject }
        ?: throw IllegalArgumentException("No transactions in this JSON")
    else -> throw IllegalArgumentException("No transactions in this JSON")
}

/** One GoCardless (Berlin Group) transaction, read the way its bank fills it in; null without an id or a date. */
fun parseGoCardless(raw: JsonObject, shape: FeedShape): ParsedRow? {
    val id = raw.str("transactionId") ?: raw.str("internalTransactionId") ?: raw.str("entryReference") ?: return null
    val booking = (raw.str("bookingDate") ?: raw.str("valueDate"))?.let(LocalDate::parse) ?: return null
    val valueDate = raw.str("valueDate")?.let(LocalDate::parse)
    val amountObject = raw.obj("transactionAmount") ?: return null
    val amount = amountObject.str("amount")?.toBigDecimalOrNull() ?: return null
    val currency = amountObject.str("currency")?.uppercase() ?: return null
    val outgoing = amount.signum() < 0

    val counterpartyName = squash((if (outgoing) raw.str("creditorName") else raw.str("debtorName")).orEmpty())
    val ref = if (outgoing) raw.obj("creditorAccount") else raw.obj("debtorAccount")
    val counterpartyIban = ref?.str("iban")?.let(::ibanOf) ?: ref?.str("bban")?.let { CzechAccount.parse(it)?.iban }
    val counterpartyAccount = counterpartyIban?.let { CzechAccount.fromIban(it)?.toString() } ?: ref?.str("bban") ?: ref?.str("maskedPan").orEmpty()
    // The message, without the payment symbols (VS/KS/SS) some banks list beside it.
    val unstructured = raw.str("remittanceInformationUnstructured")
    val message = squash(
        unstructured
            ?: (raw["remittanceInformationUnstructuredArray"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.filterNot { Regex("^[VKS]S:").containsMatchIn(it) }
                ?.joinToString(" ")?.takeIf { it.isNotBlank() }
            ?: raw.str("additionalInformation").orEmpty(),
    )

    var date = booking
    var text = counterpartyName.ifEmpty { message }
    when (shape.institution) {
        BankInstitution.REVOLUT -> text = counterpartyName.ifEmpty { message.replace(Regex("^(To|From|Payment from) "), "") }
        BankInstitution.CSOB -> parseCsobCard(unstructured.orEmpty())?.let { (cardDate, place) ->
            date = cardDate
            text = place
        }
        BankInstitution.RAIFFEISEN -> if (shape.isCard) {
            // The card's descriptor pads its fields with runs of spaces; the first is the merchant.
            text = unstructured.orEmpty().split(Regex("\\s{2,}")).first().trim().ifEmpty { message }
            if (valueDate != null) date = valueDate
        }
        else -> {}
    }

    return ParsedRow(
        externalId = id,
        bookingDate = booking,
        valueDate = valueDate,
        date = date,
        amountMinor = toMinor(amount, currency),
        currency = currency,
        counterpartyName = counterpartyName,
        counterpartyIban = counterpartyIban,
        counterpartyAccount = counterpartyAccount,
        text = text.ifEmpty { counterpartyAccount },
        message = message,
        code = if (shape.institution == BankInstitution.REVOLUT) raw.str("proprietaryBankTransactionCode").orEmpty() else "",
        raw = raw,
    )
}
