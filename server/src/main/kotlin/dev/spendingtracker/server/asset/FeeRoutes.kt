package dev.spendingtracker.server.asset

import dev.spendingtracker.server.auth.userId
import dev.spendingtracker.server.common.checkVersioned
import dev.spendingtracker.server.common.nonZeroAmount
import dev.spendingtracker.server.common.optionalText
import dev.spendingtracker.server.common.pathId
import dev.spendingtracker.server.common.requireVersion
import dev.spendingtracker.server.common.versionParam
import dev.spendingtracker.server.db.AssetFees
import dev.spendingtracker.server.db.dbQuery
import dev.spendingtracker.server.household.household
import dev.spendingtracker.shared.api.ApiError
import dev.spendingtracker.shared.api.Fee
import dev.spendingtracker.shared.api.FeeInput
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** A holding's fees, under /assets/{assetId}/fees. */
internal fun Route.feeRoutes() {
    get({
        operationId = "listFees"
        description = "The holding's fees, newest first."
        request { pathParameter<String>("assetId") }
        response {
            code(HttpStatusCode.OK) { body<List<Fee>>() }
            code(HttpStatusCode.NotFound) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val assetId = call.assetId()
        val fees = dbQuery {
            requireAsset(householdId, assetId)
            AssetFees.selectAll()
                .where { AssetFees.assetId eq assetId }
                .orderBy(AssetFees.date to SortOrder.DESC, AssetFees.id to SortOrder.ASC)
                .map { it.toFee() }
        }
        call.respond(fees)
    }

    post({
        operationId = "createFee"
        request {
            pathParameter<String>("assetId")
            body<FeeInput>()
        }
        response {
            code(HttpStatusCode.Created) { body<Fee>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.NotFound) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val assetId = call.assetId()
        val input = call.receive<FeeInput>().validated()
        val id = Uuid.random()
        val fee = dbQuery {
            requireAsset(householdId, assetId)
            AssetFees.insert {
                it[AssetFees.id] = id
                it[AssetFees.householdId] = householdId
                it[AssetFees.assetId] = assetId
                it.write(input)
                it[version] = 1
                it[createdBy] = call.userId
            }
            AssetFees.selectAll().where { AssetFees.id eq id }.single().toFee()
        }
        call.respond(HttpStatusCode.Created, fee)
    }

    put("/{feeId}", {
        operationId = "updateFee"
        request {
            pathParameter<String>("assetId")
            pathParameter<String>("feeId")
            body<FeeInput>()
        }
        response {
            code(HttpStatusCode.OK) { body<Fee>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val assetId = call.assetId()
        val id = call.pathId("feeId", "Fee")
        val raw = call.receive<FeeInput>()
        val version = requireVersion(raw.version)
        val input = raw.validated()
        val fee = dbQuery {
            val match = matchFee(householdId, assetId, id)
            val affected = AssetFees.update({ match and (AssetFees.version eq version) }) {
                it.write(input)
                it[AssetFees.version] = version + 1
                it[updatedAt] = Clock.System.now()
            }
            checkVersioned(affected, { !AssetFees.selectAll().where(match).empty() }, "Fee")
            AssetFees.selectAll().where { AssetFees.id eq id }.single().toFee()
        }
        call.respond(fee)
    }

    delete("/{feeId}", {
        operationId = "deleteFee"
        request {
            pathParameter<String>("assetId")
            pathParameter<String>("feeId")
            queryParameter<Int>("version") { required = true }
        }
        response {
            code(HttpStatusCode.NoContent) {}
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val assetId = call.assetId()
        val id = call.pathId("feeId", "Fee")
        val version = call.versionParam()
        dbQuery {
            val match = matchFee(householdId, assetId, id)
            val affected = AssetFees.deleteWhere { match and (AssetFees.version eq version) }
            checkVersioned(affected, { !AssetFees.selectAll().where(match).empty() }, "Fee")
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

private data class ValidFee(val date: LocalDate, val amountMinor: Long, val notes: String)

private fun FeeInput.validated() = ValidFee(
    date = date,
    amountMinor = nonZeroAmount(amountMinor),
    notes = optionalText(notes, "notes", max = 2000),
)

private fun UpdateBuilder<*>.write(input: ValidFee) {
    this[AssetFees.date] = input.date
    this[AssetFees.amountMinor] = input.amountMinor
    this[AssetFees.notes] = input.notes
}

private fun matchFee(householdId: Uuid, assetId: Uuid, id: Uuid) =
    (AssetFees.id eq id) and (AssetFees.householdId eq householdId) and (AssetFees.assetId eq assetId)

private fun ResultRow.toFee() = Fee(
    id = this[AssetFees.id].toString(),
    assetId = this[AssetFees.assetId].toString(),
    date = this[AssetFees.date],
    amountMinor = this[AssetFees.amountMinor],
    notes = this[AssetFees.notes],
    version = this[AssetFees.version],
)
