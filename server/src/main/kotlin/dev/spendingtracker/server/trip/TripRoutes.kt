package dev.spendingtracker.server.trip

import dev.spendingtracker.server.auth.userId
import dev.spendingtracker.server.common.badRequest
import dev.spendingtracker.server.common.checkVersioned
import dev.spendingtracker.server.common.conflict
import dev.spendingtracker.server.common.optionalText
import dev.spendingtracker.server.common.pathId
import dev.spendingtracker.server.common.requireVersion
import dev.spendingtracker.server.common.requiredText
import dev.spendingtracker.server.common.versionParam
import dev.spendingtracker.server.db.Expenses
import dev.spendingtracker.server.db.Trips
import dev.spendingtracker.server.db.dbQuery
import dev.spendingtracker.server.db.isForeignKeyViolation
import dev.spendingtracker.server.db.isUniqueViolation
import dev.spendingtracker.server.household.household
import dev.spendingtracker.shared.api.ApiError
import dev.spendingtracker.shared.api.Trip
import dev.spendingtracker.shared.api.TripInput
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Must be inside a HouseholdAccess scope. */
fun Route.tripRoutes() = route("/trips") {
    get({
        operationId = "listTrips"
        description = "The household's trips, latest first (undated ones last), each with what its expenses add up to."
        response { code(HttpStatusCode.OK) { body<List<Trip>>() } }
    }) {
        val trips = dbQuery { loadTrips(call.household.id) }
        call.respond(trips)
    }

    post({
        operationId = "createTrip"
        request { body<TripInput>() }
        response {
            code(HttpStatusCode.Created) { body<Trip>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val input = call.receive<TripInput>().validated()
        val id = Uuid.random()
        val trip = uniqueName {
            dbQuery {
                Trips.insert {
                    it[Trips.id] = id
                    it[Trips.householdId] = householdId
                    it.write(input)
                    it[createdBy] = call.userId
                }
                loadTrips(householdId, id).single()
            }
        }
        call.respond(HttpStatusCode.Created, trip)
    }

    put("/{tripId}", {
        operationId = "updateTrip"
        request {
            pathParameter<String>("tripId")
            body<TripInput>()
        }
        response {
            code(HttpStatusCode.OK) { body<Trip>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val id = call.pathId("tripId", "Trip")
        val raw = call.receive<TripInput>()
        val version = requireVersion(raw.version)
        val input = raw.validated()
        val trip = uniqueName {
            dbQuery {
                val affected = Trips.update({ match(householdId, id) and (Trips.version eq version) }) {
                    it.write(input)
                    it[Trips.version] = version + 1
                    it[updatedAt] = Clock.System.now()
                }
                checkVersioned(affected, { exists(householdId, id) }, "Trip")
                loadTrips(householdId, id).single()
            }
        }
        call.respond(trip)
    }

    delete("/{tripId}", {
        operationId = "deleteTrip"
        description = "Fails with 409 trip_in_use while expenses belong to the trip."
        request {
            pathParameter<String>("tripId")
            queryParameter<Int>("version") { required = true }
        }
        response {
            code(HttpStatusCode.NoContent) {}
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val id = call.pathId("tripId", "Trip")
        val version = call.versionParam()
        try {
            dbQuery {
                val affected = Trips.deleteWhere { match(householdId, id) and (Trips.version eq version) }
                checkVersioned(affected, { exists(householdId, id) }, "Trip")
            }
        } catch (e: Exception) {
            if (e.isForeignKeyViolation()) throw conflict("trip_in_use", "Expenses belong to this trip. Move them off it first.")
            throw e
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

private data class ValidTrip(val name: String, val startDate: LocalDate?, val endDate: LocalDate?, val notes: String)

private fun TripInput.validated(): ValidTrip {
    val start = startDate
    val end = endDate
    if (start != null && end != null && end < start) {
        throw badRequest("invalid_dates", "The trip can't end before it starts", "endDate")
    }
    return ValidTrip(requiredText(name, "name", max = 100), startDate, endDate, optionalText(notes, "notes", max = 2000))
}

private fun org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>.write(input: ValidTrip) {
    this[Trips.name] = input.name
    this[Trips.startDate] = input.startDate
    this[Trips.endDate] = input.endDate
    this[Trips.notes] = input.notes
}

/** The household's trips (or just [id]) with their expense totals. Call inside a transaction. */
private fun loadTrips(householdId: Uuid, id: Uuid? = null): List<Trip> {
    val spent = Expenses.amountMinor.sum()
    val count = Expenses.id.count()
    val totals = Expenses.select(Expenses.tripId, spent, count)
        .where { (Expenses.householdId eq householdId) and Expenses.tripId.isNotNull() }
        .groupBy(Expenses.tripId)
        .associate { it[Expenses.tripId]!! to ((it[spent] ?: 0L) to it[count].toInt()) }
    var filter = Trips.householdId eq householdId
    if (id != null) filter = filter and (Trips.id eq id)
    return Trips.selectAll().where(filter)
        .orderBy(Trips.startDate to SortOrder.DESC_NULLS_LAST, Trips.name to SortOrder.ASC)
        .map { it.toTrip(totals[it[Trips.id]] ?: (0L to 0)) }
}

private fun ResultRow.toTrip(total: Pair<Long, Int>) = Trip(
    id = this[Trips.id].toString(),
    name = this[Trips.name],
    startDate = this[Trips.startDate],
    endDate = this[Trips.endDate],
    notes = this[Trips.notes],
    version = this[Trips.version],
    spentMinor = total.first,
    expenseCount = total.second,
)

private suspend fun <T> uniqueName(block: suspend () -> T): T =
    try {
        block()
    } catch (e: Exception) {
        if (e.isUniqueViolation()) throw conflict("name_taken", "A trip with this name already exists", "name")
        throw e
    }

private fun match(householdId: Uuid, id: Uuid) = (Trips.id eq id) and (Trips.householdId eq householdId)

private fun exists(householdId: Uuid, id: Uuid) = !Trips.selectAll().where { match(householdId, id) }.empty()
