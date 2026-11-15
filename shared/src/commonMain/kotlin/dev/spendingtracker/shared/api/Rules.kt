package dev.spendingtracker.shared.api

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a rule makes of the bank rows it matches. */
@Serializable
enum class RuleOutcome {
    @SerialName("expense") EXPENSE,
    @SerialName("income") INCOME,
    @SerialName("deduction") DEDUCTION,
    @SerialName("transfer") TRANSFER,
    @SerialName("settlement") SETTLEMENT,
    @SerialName("skip") SKIP,
}

/** Which way the money moved: [IN] for a row with a positive amount, [OUT] for a negative one. */
@Serializable
enum class RuleDirection {
    @SerialName("in") IN,
    @SerialName("out") OUT,
}

/**
 * Maps bank rows to an outcome. It matches a row when every condition it sets holds: [counterpartyIban] equal to the
 * row's, [merchantKey] equal to the row's, [textContains] (normalised: upper case, no diacritics, letters and digits)
 * found in the row's text, counterparty name and message together, and the row's [feedId], [accountId] and
 * [direction]. At least one of the first three is set. When several rules match, the highest [priority] wins, then
 * the one with the most conditions, then the oldest.
 *
 * Outcome [RuleOutcome.EXPENSE] fills in [merchant], [categoryId] and [personUserId] (whose it is; null follows the
 * row's account); [RuleOutcome.INCOME] [incomeSource] and [personUserId] (who receives it); [RuleOutcome.TRANSFER]
 * [otherAccountId]; [RuleOutcome.DEDUCTION] [incomeSource] (who took it: ZPMV, OSSZ), [personUserId] (whose it is) and
 * [monthOffset] (the deduction is for the row's month plus this many months, -1 by default: the previous month),
 * and makes an income deduction of minus the row's amount; [RuleOutcome.SKIP] [skipReason]. With [auto] the row is filed without asking, otherwise the rule
 * only shapes the suggestion. [filedCount] is how many bank rows it has filed.
 */
@Serializable
data class InboxRule(
    val id: String,
    val name: String,
    val counterpartyIban: String?,
    val merchantKey: String?,
    val textContains: String?,
    val feedId: String?,
    val accountId: String?,
    val direction: RuleDirection?,
    val outcome: RuleOutcome,
    val merchant: String,
    val categoryId: String?,
    val personUserId: String?,
    val incomeSource: String,
    val otherAccountId: String?,
    val settleFromUserId: String?,
    val settleToUserId: String?,
    val skipReason: String,
    val monthOffset: Int,
    val auto: Boolean,
    val priority: Int,
    val version: Int,
    val filedCount: Int,
)

/** Body for creating and updating a rule; [version] is required when updating. */
@Serializable
data class InboxRuleInput(
    val name: String,
    val counterpartyIban: String? = null,
    val merchantKey: String? = null,
    val textContains: String? = null,
    val feedId: String? = null,
    val accountId: String? = null,
    val direction: RuleDirection? = null,
    val outcome: RuleOutcome,
    val merchant: String = "",
    val categoryId: String? = null,
    val personUserId: String? = null,
    val incomeSource: String = "",
    val otherAccountId: String? = null,
    val settleFromUserId: String? = null,
    val settleToUserId: String? = null,
    val skipReason: String = "",
    val monthOffset: Int = -1,
    val auto: Boolean = false,
    val priority: Int = 0,
    val version: Int? = null,
)

/**
 * What applying the rules to the inbox did. [checked] pending rows were looked at; [filedByRules] were filed by rules
 * with auto, [linkedByRules] of them under a record that was already there instead of a new one; the matcher filed
 * [pairedByMatcher] rows as transfer pairs and [duplicatesByMatcher] as duplicates of hand-entered expenses (only
 * when the household lets it). Rows nothing applies to stay in the inbox.
 */
@Serializable
data class ApplyRulesResult(
    val checked: Int,
    val filedByRules: Int,
    val linkedByRules: Int,
    val pairedByMatcher: Int,
    val duplicatesByMatcher: Int,
)

/** Household settings owners can change. [autoMatch]: the inbox matcher files transfer pairs and duplicates without asking. [debtsFrom]: expenses and settlements before this date don't count toward balances. */
@Serializable
data class HouseholdSettings(val autoMatch: Boolean, val debtsFrom: LocalDate?)
