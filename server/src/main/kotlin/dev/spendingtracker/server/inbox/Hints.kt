package dev.spendingtracker.server.inbox

import dev.spendingtracker.shared.api.BankInstitution
import kotlin.uuid.Uuid

/** What the household knows that tells its own money movements apart. */
class HintContext(
    /** IBANs of the household's accounts and feeds. */
    val ownIbans: Set<String>,
    /** Members' names, as banks may write them on a transfer between them. */
    val memberNames: List<String>,
    /** Whose account each of [ownIbans] is: a member, or null for a joint account. An IBAN missing here counts as the row's own. */
    val ownerByIban: Map<String, Uuid?> = emptyMap(),
    /** Each member's names (display and legal). */
    val namesByMember: Map<Uuid, List<String>> = emptyMap(),
)

/** Money between two accounts of the same member, or two joint accounts. */
const val OWN_ACCOUNTS_HINT = "Between your own accounts"

/** Money between a member's account and a joint one: a top-up or a withdrawal. */
const val HOUSEHOLD_ACCOUNTS_HINT = "Between household accounts"

/** Money between two members' accounts: a settlement, a loan, or money that goes round. */
const val MEMBERS_ACCOUNTS_HINT = "Between members' accounts"

/** Money to or from an account in the name of the account's owner that the app doesn't read. */
const val OWN_NAME_HINT = "To or from your own account"

/** Money to or from an account in another member's name that the app doesn't read. */
const val MEMBER_NAME_HINT = "To or from someone in the household"

/** Which of the hints on money between the household's own accounts fits, from whose account each side is. */
fun ownAccountsHint(thisOwner: Uuid?, otherOwner: Uuid?): String = when {
    thisOwner == otherOwner -> OWN_ACCOUNTS_HINT
    thisOwner == null || otherOwner == null -> HOUSEHOLD_ACCOUNTS_HINT
    else -> MEMBERS_ACCOUNTS_HINT
}

/** Which of the hints on money to or from a member's name fits: the account owner's own name, or someone else's; null for neither. */
fun memberNameHint(counterpartyName: String, accountOwner: Uuid?, context: HintContext): String? {
    val named = context.namesByMember.filterValues { isHouseholdName(counterpartyName, it) }.keys
    if (accountOwner != null && named == setOf(accountOwner)) return OWN_NAME_HINT
    return if (isHouseholdName(counterpartyName, context.memberNames)) MEMBER_NAME_HINT else null
}

/** A card payment to "Revolut**1234*" is a top-up of a Revolut account. */
private val REVOLUT_TOPUP = Regex("revolut\\*+\\d+\\*?", RegexOption.IGNORE_CASE)
private val CARD_REPAYMENT = Regex("spl[aá]tka klienta", RegexOption.IGNORE_CASE)

/** What banks call interest paid out: "Úrok", "Připsaný úrok", "Interest". */
private val INTEREST = Regex("[uú]rok|interest", RegexOption.IGNORE_CASE)

/** The hint on interest paid into a savings account. */
const val INTEREST_HINT = "Interest"

/**
 * What the reader notices about a row, for whoever decides it: money between the household's own accounts (saying whose
 * accounts: one member's, a member's and a joint one, two members'), to or from a member's name, a top-up,
 * a currency exchange, a card being paid off, a refund. Only a hint: nothing is filed on it in this slice.
 */
fun hintFor(row: ParsedRow, shape: FeedShape, context: HintContext): String {
    val iban = row.counterpartyIban
    if (iban != null && iban in context.ownIbans) {
        return if (iban in context.ownerByIban) ownAccountsHint(shape.ownerUserId, context.ownerByIban[iban]) else OWN_ACCOUNTS_HINT
    }
    // Money in on a savings account from nobody, said to be interest or saying nothing at all (ČSOB books it at the
    // month's end with no text), is the interest it pays.
    if (shape.isSavings && row.amountMinor > 0 && row.counterpartyIban == null && row.counterpartyName.isEmpty() &&
        (INTEREST.containsMatchIn(row.text) || INTEREST.containsMatchIn(row.message) || (row.text.isBlank() && row.message.isBlank()))
    ) {
        return INTEREST_HINT
    }
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
    if (!isCardPayment) memberNameHint(row.counterpartyName, shape.ownerUserId, context)?.let { return it }
    if (row.amountMinor > 0 && shape.isCard) return "Money in on a card: a refund?"
    return ""
}
