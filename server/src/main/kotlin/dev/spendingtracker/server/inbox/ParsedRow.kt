package dev.spendingtracker.server.inbox

import dev.spendingtracker.shared.api.BankInstitution
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject

/** One bank transaction as read from a statement or the bank's API, before it's stored as a bank row. */
data class ParsedRow(
    val externalId: String,
    val bookingDate: LocalDate,
    val valueDate: LocalDate?,
    /** When it happened: a card payment's own date where the bank gives it. */
    val date: LocalDate,
    /** Minor units of [currency]; money out is negative. */
    val amountMinor: Long,
    val currency: String,
    val counterpartyName: String,
    val counterpartyIban: String?,
    val counterpartyAccount: String,
    /** Names who was paid or who paid. */
    val text: String,
    val message: String,
    /** The bank's own transaction type, normalised to Revolut's codes where it's Revolut (CARD_PAYMENT, TOPUP…). */
    val code: String,
    val raw: JsonObject,
)

/** How rows of a feed are read. [isCard] when the feed's account is a credit card. */
/** How a feed is read: its bank, and whether its account is a credit card or a savings account. */
data class FeedShape(val institution: BankInstitution, val isCard: Boolean, val isSavings: Boolean = false)

val BankInstitution.dbValue get() = name.lowercase()

fun bankInstitution(dbValue: String) = BankInstitution.entries.first { it.dbValue == dbValue }
