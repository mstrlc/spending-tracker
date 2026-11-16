package dev.spendingtracker.server.inbox

import dev.spendingtracker.server.db.Accounts
import dev.spendingtracker.server.db.BankFeeds
import dev.spendingtracker.server.db.BankRows
import dev.spendingtracker.server.db.HouseholdMembers
import dev.spendingtracker.server.db.Users
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

/** Hints the reader gives a row that moves money between the household's own accounts. */
val MOVEMENT_HINTS = setOf(
    OWN_ACCOUNTS_HINT, HOUSEHOLD_ACCOUNTS_HINT, MEMBERS_ACCOUNTS_HINT, "Revolut top-up", "Top-up", "Currency exchange",
    "Card repayment", MEMBER_NAME_HINT, OWN_NAME_HINT,
)

/**
 * Whether two bank rows of the same amount, one out and one in, are the two sides of one transfer. The same amount a
 * few days apart is common (1 000 Kč can be anything), so it takes more than that:
 *
 * - **Evidence** for it: either row's counterparty IBAN is the other's account; either row names a card of the other's
 *   account ("Top-Up by *3656"); both are on one account (pockets of one
 *   account); both carry a hint that money moved between own accounts (a Revolut top-up and the card payment that paid
 *   for it); or either row's text names the other's account or its owner.
 * - **No evidence against it**: a counterparty IBAN that belongs to another account, or to none of the household's,
 *   says the money went somewhere else; so does a card that's another account's.
 *
 * The old app's hints ("Old app: …") are only hints and count for nothing here.
 */
class TransferEvidence(
    private val accountByIban: Map<String, Uuid>,
    private val accountNames: Map<Uuid, String>,
    private val ownerNames: Map<Uuid, List<String>>,
    private val accountByCard: Map<String, Uuid> = emptyMap(),
) {
    /** The account whose card [row] says paid ("Top-Up by *3656"); null when it names none, or one no account has. */
    fun cardAccount(row: ResultRow): Uuid? =
        CARD_IN_TEXT.find(row[BankRows.text])?.groupValues?.get(1)?.let(accountByCard::get)

    /** True when [a] and [b] may be one transfer: some evidence for it, none against it. */
    fun supports(a: ResultRow, b: ResultRow): Boolean = strength(a, b) > 0

    /**
     * How much says [a] and [b] are one transfer: 0 when nothing does or something says otherwise, more for each thing
     * that does. An IBAN pointing at the other account counts most; a row naming its own account says little.
     */
    fun strength(a: ResultRow, b: ResultRow): Int {
        if (contradicted(a, b) || contradicted(b, a)) return 0
        val accountA = a[BankFeeds.accountId]
        val accountB = b[BankFeeds.accountId]
        return listOf(
            3 to (a[BankRows.counterpartyIban]?.let(accountByIban::get) == accountB),
            3 to (b[BankRows.counterpartyIban]?.let(accountByIban::get) == accountA),
            3 to (cardAccount(a) == accountB),
            3 to (cardAccount(b) == accountA),
            2 to (accountA == accountB),
            2 to (isMovement(a) && isMovement(b)),
            1 to names(a, accountB),
            1 to names(b, accountA),
        ).sumOf { (weight, holds) -> if (holds) weight else 0 }
    }

    /** [row]'s counterparty IBAN is known and isn't [other]'s account. */
    private fun contradicted(row: ResultRow, other: ResultRow): Boolean {
        val card = cardAccount(row)
        if (card != null && card != other[BankFeeds.accountId]) return true
        val iban = row[BankRows.counterpartyIban] ?: return false
        return accountByIban[iban] != other[BankFeeds.accountId]
    }

    /** [row]'s counterparty or text names [account] or its owner. */
    private fun names(row: ResultRow, account: Uuid): Boolean {
        val said = listOf(row[BankRows.counterpartyName], row[BankRows.text], row[BankRows.message]).filter { it.isNotBlank() }
        val candidates = listOfNotNull(accountNames[account]) + ownerNames[account].orEmpty()
        return said.any { isHouseholdName(it, candidates) }
    }

    companion object {
        /** How Revolut names the card a top-up came from: "Apple Pay Top-Up by *3656", "Top-Up by *3656". */
        private val CARD_IN_TEXT = Regex("\\bby \\*(\\d{4})\\b", RegexOption.IGNORE_CASE)

        fun isMovement(row: ResultRow) = row[BankRows.hint] in MOVEMENT_HINTS

        /** Loads what [householdId] knows about its accounts. Call inside a transaction. */
        fun load(householdId: Uuid): TransferEvidence {
            val accounts = Accounts.select(Accounts.id, Accounts.name, Accounts.ownerUserId, Accounts.iban, Accounts.cards)
                .where { Accounts.householdId eq householdId }.toList()
            val memberNames = HouseholdMembers.join(Users, JoinType.INNER, HouseholdMembers.userId, Users.id)
                .select(HouseholdMembers.userId, Users.displayName, HouseholdMembers.legalName)
                .where { HouseholdMembers.householdId eq householdId }
                .associate { r ->
                    r[HouseholdMembers.userId] to listOfNotNull(r[Users.displayName], r[HouseholdMembers.legalName].takeIf { it.isNotBlank() })
                }
            // An IBAN's account: a feed's, else an account's own.
            val byIban = accounts.mapNotNull { r -> r[Accounts.iban]?.let { it to r[Accounts.id] } }.toMap() +
                BankFeeds.select(BankFeeds.iban, BankFeeds.accountId).where { BankFeeds.householdId eq householdId }
                    .mapNotNull { r -> r[BankFeeds.iban]?.let { it to r[BankFeeds.accountId] } }.toMap()
            return TransferEvidence(
                accountByIban = byIban,
                accountNames = accounts.associate { it[Accounts.id] to it[Accounts.name] },
                ownerNames = accounts.associate { r -> r[Accounts.id] to (r[Accounts.ownerUserId]?.let(memberNames::get) ?: emptyList()) },
                accountByCard = accounts.flatMap { r -> r[Accounts.cards].split(' ').filter { it.isNotEmpty() }.map { it to r[Accounts.id] } }.toMap(),
            )
        }
    }
}
