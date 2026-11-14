package dev.spendingtracker.server.debt

import dev.spendingtracker.server.common.badRequest
import dev.spendingtracker.server.common.parseId
import dev.spendingtracker.server.db.SplitRules
import dev.spendingtracker.server.db.SplitShares
import dev.spendingtracker.server.household.isMember
import dev.spendingtracker.shared.api.Share
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val MAX_WEIGHT = 1_000_000

/** Weights by member from API shares: a weight of 0 means no part; at least one member needs a part. */
fun List<Share>.validatedWeights(field: String = "shares"): Weights {
    val weights = mutableMapOf<Uuid, Int>()
    for (share in this) {
        val userId = parseId(share.userId, field) ?: throw badRequest("invalid_member", "Every share needs a member", field)
        if (share.weight < 0 || share.weight > MAX_WEIGHT) {
            throw badRequest("invalid_weight", "Shares are whole numbers from 0 to $MAX_WEIGHT", field)
        }
        if (userId in weights) throw badRequest("duplicate_member", "Each member can have only one share", field)
        weights[userId] = share.weight
    }
    return weights.filterValues { it > 0 }.ifEmpty {
        throw badRequest("empty_split", "Give at least one member a share", field)
    }
}

/** Call inside a transaction. */
fun Weights.checkMembers(householdId: Uuid, field: String = "shares") {
    if (keys.any { !isMember(householdId, it) }) throw badRequest("invalid_member", "Shares must belong to members of the household", field)
}

fun Weights.toShares(): List<Share> = entries.sortedBy { it.key.toString() }.map { Share(it.key.toString(), it.value) }

/** Brings [ruleId]'s shares to [weights], touching only the members whose share changed, so the log shows just that. */
fun writeShares(householdId: Uuid, ruleId: Uuid, weights: Weights) {
    val current = SplitShares.selectAll().where { SplitShares.ruleId eq ruleId }
        .associate { it[SplitShares.userId] to it[SplitShares.weight] }
    val gone = current.keys - weights.keys
    if (gone.isNotEmpty()) SplitShares.deleteWhere { (SplitShares.ruleId eq ruleId) and (SplitShares.userId inList gone) }
    for ((userId, weight) in weights) {
        when (current[userId]) {
            weight -> Unit
            null -> SplitShares.insert {
                it[SplitShares.householdId] = householdId
                it[SplitShares.ruleId] = ruleId
                it[SplitShares.userId] = userId
                it[SplitShares.weight] = weight
            }
            else -> SplitShares.update({ (SplitShares.ruleId eq ruleId) and (SplitShares.userId eq userId) }) {
                it[SplitShares.weight] = weight
            }
        }
    }
}

/** Gives [expenseId] its own split, or takes it away when [weights] is null. Call inside a transaction. */
fun writeExpenseSplit(householdId: Uuid, expenseId: Uuid, weights: Weights?, actor: Uuid) {
    val rule = SplitRules.selectAll().where { SplitRules.expenseId eq expenseId }.singleOrNull()?.get(SplitRules.id)
    when {
        weights == null -> if (rule != null) SplitRules.deleteWhere { SplitRules.id eq rule }
        rule == null -> {
            val id = Uuid.random()
            SplitRules.insert {
                it[SplitRules.id] = id
                it[SplitRules.householdId] = householdId
                it[SplitRules.expenseId] = expenseId
                it[createdBy] = actor
            }
            writeShares(householdId, id, weights)
        }
        else -> {
            writeShares(householdId, rule, weights)
            SplitRules.update({ SplitRules.id eq rule }) { it[updatedAt] = Clock.System.now() }
        }
    }
}
