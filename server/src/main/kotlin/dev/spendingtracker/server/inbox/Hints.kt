package dev.spendingtracker.server.inbox

import dev.spendingtracker.shared.api.BankInstitution

/** What the household knows that tells its own money movements apart. */
class HintContext(
    /** IBANs of the household's accounts and feeds. */
    val ownIbans: Set<String>,
    /** Members' names, as banks may write them on a transfer between them. */
    val memberNames: List<String>,
)

/** A card payment to "Revolut**1234*" is a top-up of a Revolut account. */
private val REVOLUT_TOPUP = Regex("revolut\\*+\\d+\\*?", RegexOption.IGNORE_CASE)
private val CARD_REPAYMENT = Regex("spl[aá]tka klienta", RegexOption.IGNORE_CASE)

/**
 * What the reader notices about a row, for whoever decides it: money between the household's own accounts, a top-up,
 * a currency exchange, a card being paid off, a refund. Only a hint: nothing is filed on it in this slice.
 */
fun hintFor(row: ParsedRow, shape: FeedShape, context: HintContext): String {
    if (row.counterpartyIban != null && row.counterpartyIban in context.ownIbans) return "Between your own accounts"
    if (REVOLUT_TOPUP.containsMatchIn(row.text) || REVOLUT_TOPUP.containsMatchIn(row.message)) return "Revolut top-up"
    if (CARD_REPAYMENT.containsMatchIn(row.message) || CARD_REPAYMENT.containsMatchIn(row.text)) return "Card repayment"
    if (shape.institution == BankInstitution.REVOLUT) {
        when (row.code) {
            "TOPUP" -> return "Top-up"
            "EXCHANGE" -> return "Currency exchange"
            "CARD_REFUND" -> return "Refund"
        }
    }
    val isCardPayment = row.counterpartyName.isEmpty() || row.code == "CARD_PAYMENT"
    if (!isCardPayment && isHouseholdName(row.counterpartyName, context.memberNames)) return "To or from someone in the household"
    if (row.amountMinor > 0 && shape.isCard) return "Money in on a card: a refund?"
    return ""
}
