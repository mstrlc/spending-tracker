package dev.spendingtracker.server.inbox

import dev.spendingtracker.server.account.normalizeIban
import dev.spendingtracker.server.auth.userId
import dev.spendingtracker.server.common.ApiException
import dev.spendingtracker.server.common.badRequest
import dev.spendingtracker.server.common.checkVersioned
import dev.spendingtracker.server.common.optionalText
import dev.spendingtracker.server.common.parseId
import dev.spendingtracker.server.common.pathId
import dev.spendingtracker.server.common.requireVersion
import dev.spendingtracker.server.common.requiredText
import dev.spendingtracker.server.common.versionParam
import dev.spendingtracker.server.db.Accounts
import dev.spendingtracker.server.db.BankFeeds
import dev.spendingtracker.server.db.BankRows
import dev.spendingtracker.server.db.Categories
import dev.spendingtracker.server.db.InboxRules
import dev.spendingtracker.server.db.dbQuery
import dev.spendingtracker.server.household.household
import dev.spendingtracker.server.household.isMember
import dev.spendingtracker.server.market.market
import dev.spendingtracker.shared.api.ApiError
import dev.spendingtracker.shared.api.ApplyRulesResult
import dev.spendingtracker.shared.api.InboxRule
import dev.spendingtracker.shared.api.InboxRuleInput
import dev.spendingtracker.shared.api.RuleDirection
import dev.spendingtracker.shared.api.RuleOutcome
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val MAX_PRIORITY = 1_000
private const val MAX_MONTH_OFFSET = 12
private const val DEFAULT_MONTH_OFFSET = -1

/** Must be inside a HouseholdAccess scope. */
fun Route.ruleRoutes() {
    route("/inbox-rules") {
        get({
            operationId = "listInboxRules"
            description = "The household's inbox rules in the order they win: highest priority first, then the most specific, then the oldest."
            response { code(HttpStatusCode.OK) { body<List<InboxRule>>() } }
        }) {
            call.respond(dbQuery { loadInboxRules(call.household.id) })
        }

        post({
            operationId = "createInboxRule"
            description = "Needs at least one of counterpartyIban, merchantKey and textContains. A rule with auto files matching rows " +
                "(now, when the rules are applied, and on every statement or import after); without, it only shapes the suggestion."
            request { body<InboxRuleInput>() }
            response {
                code(HttpStatusCode.Created) { body<InboxRule>() }
                code(HttpStatusCode.BadRequest) { body<ApiError>() }
            }
        }) {
            val householdId = call.household.id
            val input = call.receive<InboxRuleInput>().validated()
            val id = Uuid.random()
            val rule = dbQuery {
                input.checkReferences(householdId)
                InboxRules.insert {
                    it[InboxRules.id] = id
                    it[InboxRules.householdId] = householdId
                    it.write(input)
                    it[createdBy] = call.userId
                }
                loadInboxRules(householdId, id).single()
            }
            call.respond(HttpStatusCode.Created, rule)
        }

        put("/{ruleId}", {
            operationId = "updateInboxRule"
            description = "Changes the rule. Rows it filed stay as they are."
            request {
                pathParameter<String>("ruleId")
                body<InboxRuleInput>()
            }
            response {
                code(HttpStatusCode.OK) { body<InboxRule>() }
                code(HttpStatusCode.BadRequest) { body<ApiError>() }
                code(HttpStatusCode.NotFound) { body<ApiError>() }
                code(HttpStatusCode.Conflict) { body<ApiError>() }
            }
        }) {
            val householdId = call.household.id
            val id = call.pathId("ruleId", "Rule")
            val raw = call.receive<InboxRuleInput>()
            val version = requireVersion(raw.version)
            val input = raw.validated()
            val rule = dbQuery {
                input.checkReferences(householdId)
                val affected = InboxRules.update({ match(householdId, id) and (InboxRules.version eq version) }) {
                    it.write(input)
                    it[InboxRules.version] = version + 1
                    it[updatedAt] = Clock.System.now()
                }
                checkVersioned(affected, { exists(householdId, id) }, "Rule")
                loadInboxRules(householdId, id).single()
            }
            call.respond(rule)
        }

        delete("/{ruleId}", {
            operationId = "deleteInboxRule"
            description = "Deletes the rule. Rows it filed stay filed."
            request {
                pathParameter<String>("ruleId")
                queryParameter<Int>("version") { required = true }
            }
            response {
                code(HttpStatusCode.NoContent) {}
                code(HttpStatusCode.NotFound) { body<ApiError>() }
                code(HttpStatusCode.Conflict) { body<ApiError>() }
            }
        }) {
            val householdId = call.household.id
            val id = call.pathId("ruleId", "Rule")
            val version = call.versionParam()
            dbQuery {
                val affected = InboxRules.deleteWhere { match(householdId, id) and (InboxRules.version eq version) }
                checkVersioned(affected, { exists(householdId, id) }, "Rule")
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }

    route("/bank-rows") {
        post("/apply-rules", {
            operationId = "applyInboxRules"
            description = "Files the pending rows that rules with auto speak for and, when the household allows it, the transfer pairs " +
                "and duplicates the matcher is sure of. Statements and imports do this for the rows they add; this does it for everything pending."
            response { code(HttpStatusCode.OK) { body<ApplyRulesResult>() } }
        }) {
            val household = call.household
            // Foreign rows are converted at ČNB fixings: bring the years in question up to date first.
            val years = dbQuery {
                BankRows.select(BankRows.date).where {
                    (BankRows.householdId eq household.id) and pendingRows() and (BankRows.currency neq household.currency)
                }.map { it[BankRows.date].year }.distinct()
            }
            if (years.isNotEmpty()) call.application.market.refresh(emptyList(), years)
            val result = dbQuery { applyRules(household.id, household.currency, call.userId) }
            call.respond(result)
        }
    }
}

private data class ValidRule(
    val name: String,
    val counterpartyIban: String?,
    val merchantKey: String?,
    val textContains: String?,
    val feedId: Uuid?,
    val accountId: Uuid?,
    val direction: RuleDirection?,
    val outcome: RuleOutcome,
    val merchant: String,
    val categoryId: Uuid?,
    val personUserId: Uuid?,
    val incomeSource: String,
    val otherAccountId: Uuid?,
    val skipReason: String,
    val monthOffset: Int,
    val auto: Boolean,
    val priority: Int,
)

private fun InboxRuleInput.validated(): ValidRule {
    val iban = try {
        counterpartyIban?.trim()?.takeIf { it.isNotEmpty() }?.let { normalizeIban(ibanOf(it) ?: it) }
    } catch (e: ApiException) {
        throw badRequest(e.code, e.message, "counterpartyIban")
    }
    val key = merchantKey?.trim()?.takeIf { it.isNotEmpty() }?.let {
        normalizeText(it).ifEmpty { throw badRequest("invalid_merchant_key", "Use letters or digits", "merchantKey") }
    }
    val text = textContains?.trim()?.takeIf { it.isNotEmpty() }?.let {
        normalizeText(it).ifEmpty { throw badRequest("invalid_text", "Use letters or digits", "textContains") }
    }
    if (iban == null && key == null && text == null) {
        throw badRequest("rule_needs_match", "Match on a counterparty IBAN, a merchant key or some text", "textContains")
    }
    if (priority !in -MAX_PRIORITY..MAX_PRIORITY) throw badRequest("invalid_priority", "Priority must be between -$MAX_PRIORITY and $MAX_PRIORITY", "priority")
    if (outcome == RuleOutcome.DEDUCTION && monthOffset !in -MAX_MONTH_OFFSET..MAX_MONTH_OFFSET) {
        throw badRequest("invalid_month_offset", "The month offset must be between -$MAX_MONTH_OFFSET and $MAX_MONTH_OFFSET", "monthOffset")
    }
    val person = parseId(personUserId, "personUserId")
    return ValidRule(
        name = requiredText(name, "name", max = 100),
        counterpartyIban = iban,
        merchantKey = key,
        textContains = text,
        feedId = parseId(feedId, "feedId"),
        accountId = parseId(accountId, "accountId"),
        direction = direction,
        outcome = outcome,
        // What an outcome doesn't use is left empty, so a changed outcome doesn't keep stale settings.
        merchant = if (outcome == RuleOutcome.EXPENSE) optionalText(merchant, "merchant", max = 200) else "",
        categoryId = if (outcome == RuleOutcome.EXPENSE) parseId(categoryId, "categoryId") else null,
        personUserId = if (outcome == RuleOutcome.EXPENSE || outcome == RuleOutcome.INCOME || outcome == RuleOutcome.DEDUCTION) person else null,
        incomeSource = if (outcome == RuleOutcome.INCOME || outcome == RuleOutcome.DEDUCTION) optionalText(incomeSource, "incomeSource", max = 200) else "",
        otherAccountId = if (outcome == RuleOutcome.TRANSFER) {
            parseId(otherAccountId, "otherAccountId") ?: throw badRequest("required", "Say which account the other side is", "otherAccountId")
        } else {
            null
        },
        skipReason = if (outcome == RuleOutcome.SKIP) requiredText(skipReason, "skipReason", max = 200) else "",
        monthOffset = if (outcome == RuleOutcome.DEDUCTION) monthOffset else DEFAULT_MONTH_OFFSET,
        auto = auto,
        priority = priority,
    )
}

/** Field-level errors for references the composite foreign keys would otherwise reject opaquely. */
private fun ValidRule.checkReferences(householdId: Uuid) {
    if (feedId != null && BankFeeds.selectAll().where { (BankFeeds.id eq feedId) and (BankFeeds.householdId eq householdId) }.empty()) {
        throw badRequest("invalid_feed", "Unknown bank feed", "feedId")
    }
    for ((id, field) in listOf(accountId to "accountId", otherAccountId to "otherAccountId")) {
        if (id != null && Accounts.selectAll().where { (Accounts.id eq id) and (Accounts.householdId eq householdId) }.empty()) {
            throw badRequest("invalid_account", "Unknown account", field)
        }
    }
    if (categoryId != null && Categories.selectAll().where { (Categories.id eq categoryId) and (Categories.householdId eq householdId) }.empty()) {
        throw badRequest("invalid_category", "Unknown category", "categoryId")
    }
    if (personUserId != null && !isMember(householdId, personUserId)) {
        throw badRequest("invalid_member", "Must be a member of the household", "personUserId")
    }
}

private fun org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>.write(input: ValidRule) {
    this[InboxRules.name] = input.name
    this[InboxRules.counterpartyIban] = input.counterpartyIban
    this[InboxRules.merchantKey] = input.merchantKey
    this[InboxRules.textContains] = input.textContains
    this[InboxRules.feedId] = input.feedId
    this[InboxRules.accountId] = input.accountId
    this[InboxRules.direction] = input.direction?.dbValue
    this[InboxRules.outcome] = input.outcome.dbValue
    this[InboxRules.merchant] = input.merchant
    this[InboxRules.categoryId] = input.categoryId
    this[InboxRules.personUserId] = input.personUserId
    this[InboxRules.incomeSource] = input.incomeSource
    this[InboxRules.otherAccountId] = input.otherAccountId
    this[InboxRules.skipReason] = input.skipReason
    this[InboxRules.monthOffset] = input.monthOffset
    this[InboxRules.auto] = input.auto
    this[InboxRules.priority] = input.priority
}

private fun match(householdId: Uuid, id: Uuid) = (InboxRules.id eq id) and (InboxRules.householdId eq householdId)

private fun exists(householdId: Uuid, id: Uuid) = !InboxRules.selectAll().where { match(householdId, id) }.empty()

/** The household's rules (or just [id]) in the order they win, with how many rows each has filed. Call inside a transaction. */
private fun loadInboxRules(householdId: Uuid, id: Uuid? = null): List<InboxRule> {
    val filedCount = BankRows.id.count()
    val filed = BankRows.select(BankRows.ruleId, filedCount)
        .where { (BankRows.householdId eq householdId) and BankRows.ruleId.isNotNull() }
        .groupBy(BankRows.ruleId)
        .associate { it[BankRows.ruleId]!! to it[filedCount].toInt() }
    return loadRules(householdId).filter { id == null || it.id == id }.map { rule ->
        InboxRule(
            id = rule.id.toString(),
            name = rule.name,
            counterpartyIban = rule.counterpartyIban,
            merchantKey = rule.merchantKey,
            textContains = rule.textContains,
            feedId = rule.feedId?.toString(),
            accountId = rule.accountId?.toString(),
            direction = rule.direction,
            outcome = rule.outcome,
            merchant = rule.merchant,
            categoryId = rule.categoryId?.toString(),
            personUserId = rule.personUserId?.toString(),
            incomeSource = rule.incomeSource,
            otherAccountId = rule.otherAccountId?.toString(),
            skipReason = rule.skipReason,
            monthOffset = rule.monthOffset,
            auto = rule.auto,
            priority = rule.priority,
            version = rule.version,
            filedCount = filed[rule.id] ?: 0,
        )
    }
}
