package dev.spendingtracker.server.investment

import dev.spendingtracker.server.auth.userId
import dev.spendingtracker.server.common.checkVersioned
import dev.spendingtracker.server.common.nonZeroAmount
import dev.spendingtracker.server.common.optionalText
import dev.spendingtracker.server.common.pathId
import dev.spendingtracker.server.common.requireVersion
import dev.spendingtracker.server.common.versionParam
import dev.spendingtracker.server.db.InvestmentFees
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

/** A holding's fees, under /investments/{investmentId}/fees. */
internal fun Route.feeRoutes() {
    get({
        operationId = "listFees"
        description = "The holding's fees, newest first."
        request { pathParameter<String>("investmentId") }
        response {
            code(HttpStatusCode.OK) { body<List<Fee>>() }
            code(HttpStatusCode.NotFound) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val investmentId = call.investmentId()
        val fees = dbQuery {
            requireInvestment(householdId, investmentId)
            InvestmentFees.selectAll()
                .where { InvestmentFees.investmentId eq investmentId }
                .orderBy(InvestmentFees.date to SortOrder.DESC, InvestmentFees.id to SortOrder.ASC)
                .map { it.toFee() }
        }
        call.respond(fees)
    }

    post({
        operationId = "createFee"
        request {
            pathParameter<String>("investmentId")
            body<FeeInput>()
        }
        response {
            code(HttpStatusCode.Created) { body<Fee>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.NotFound) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val investmentId = call.investmentId()
        val input = call.receive<FeeInput>().validated()
        val id = Uuid.random()
        val fee = dbQuery {
            requireInvestment(householdId, investmentId)
            InvestmentFees.insert {
                it[InvestmentFees.id] = id
                it[InvestmentFees.householdId] = householdId
                it[InvestmentFees.investmentId] = investmentId
                it.write(input)
                it[version] = 1
                it[createdBy] = call.userId
            }
            InvestmentFees.selectAll().where { InvestmentFees.id eq id }.single().toFee()
        }
        call.respond(HttpStatusCode.Created, fee)
    }

    put("/{feeId}", {
        operationId = "updateFee"
        request {
            pathParameter<String>("investmentId")
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
        val investmentId = call.investmentId()
        val id = call.pathId("feeId", "Fee")
        val raw = call.receive<FeeInput>()
        val version = requireVersion(raw.version)
        val input = raw.validated()
        val fee = dbQuery {
            val match = matchFee(householdId, investmentId, id)
            val affected = InvestmentFees.update({ match and (InvestmentFees.version eq version) }) {
                it.write(input)
                it[InvestmentFees.version] = version + 1
                it[updatedAt] = Clock.System.now()
            }
            checkVersioned(affected, { !InvestmentFees.selectAll().where(match).empty() }, "Fee")
            InvestmentFees.selectAll().where { InvestmentFees.id eq id }.single().toFee()
        }
        call.respond(fee)
    }

    delete("/{feeId}", {
        operationId = "deleteFee"
        request {
            pathParameter<String>("investmentId")
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
        val investmentId = call.investmentId()
        val id = call.pathId("feeId", "Fee")
        val version = call.versionParam()
        dbQuery {
            val match = matchFee(householdId, investmentId, id)
            val affected = InvestmentFees.deleteWhere { match and (InvestmentFees.version eq version) }
            checkVersioned(affected, { !InvestmentFees.selectAll().where(match).empty() }, "Fee")
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
    this[InvestmentFees.date] = input.date
    this[InvestmentFees.amountMinor] = input.amountMinor
    this[InvestmentFees.notes] = input.notes
}

private fun matchFee(householdId: Uuid, investmentId: Uuid, id: Uuid) =
    (InvestmentFees.id eq id) and (InvestmentFees.householdId eq householdId) and (InvestmentFees.investmentId eq investmentId)

private fun ResultRow.toFee() = Fee(
    id = this[InvestmentFees.id].toString(),
    investmentId = this[InvestmentFees.investmentId].toString(),
    date = this[InvestmentFees.date],
    amountMinor = this[InvestmentFees.amountMinor],
    notes = this[InvestmentFees.notes],
    version = this[InvestmentFees.version],
)
