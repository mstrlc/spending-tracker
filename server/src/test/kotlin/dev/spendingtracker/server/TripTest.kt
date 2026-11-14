package dev.spendingtracker.server

import dev.spendingtracker.shared.api.Expense
import dev.spendingtracker.shared.api.ExpenseInput
import dev.spendingtracker.shared.api.Trip
import dev.spendingtracker.shared.api.TripInput
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class TripTest {
    @Test
    fun tripsAddUpTheirExpenses() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"

        val slovenia = client.post("$base/trips") {
            setBody(TripInput(" Slovinsko ", LocalDate(2026, 8, 22), LocalDate(2026, 8, 31)))
        }.body<Trip>()
        assertEquals("Slovinsko" to 0L, slovenia.name to slovenia.spentMinor)
        client.post("$base/trips") { setBody(TripInput("Slovinsko")) }.assertError(HttpStatusCode.Conflict, "name_taken")
        client.post("$base/trips") { setBody(TripInput("Back to front", LocalDate(2026, 9, 2), LocalDate(2026, 9, 1))) }
            .assertError(HttpStatusCode.BadRequest, "invalid_dates")

        // Paid in euros, counted in crowns.
        val lunch = client.post("$base/expenses") {
            setBody(ExpenseInput(LocalDate(2026, 8, 23), 615_00, "Gostilna", tripId = slovenia.id, originalAmountMinor = 25_00, originalCurrency = "eur"))
        }.body<Expense>()
        assertEquals(Triple(slovenia.id, 25_00L, "EUR"), Triple(lunch.tripId, lunch.originalAmountMinor, lunch.originalCurrency))
        client.post("$base/expenses") { setBody(ExpenseInput(LocalDate(2026, 8, 24), 4_000_00, "Kemp", tripId = slovenia.id)) }
        client.post("$base/expenses") { setBody(ExpenseInput(LocalDate(2026, 8, 24), 1_00, originalAmountMinor = 1)) }
            .assertError(HttpStatusCode.BadRequest, "original_incomplete")
        client.post("$base/expenses") { setBody(ExpenseInput(LocalDate(2026, 8, 24), 1_00, tripId = lunch.id)) }
            .assertError(HttpStatusCode.BadRequest, "invalid_trip")

        val listed = client.get("$base/trips").body<List<Trip>>().single()
        assertEquals(4_615_00L to 2, listed.spentMinor to listed.expenseCount)

        // A trip with expenses stays; renamed it keeps them.
        client.delete("$base/trips/${slovenia.id}?version=${slovenia.version}").assertError(HttpStatusCode.Conflict, "trip_in_use")
        val renamed = client.put("$base/trips/${slovenia.id}") { setBody(TripInput("Slovenia", version = slovenia.version)) }.body<Trip>()
        assertEquals(Triple("Slovenia", null, 2), Triple(renamed.name, renamed.startDate, renamed.expenseCount))

        val empty = client.post("$base/trips") { setBody(TripInput("Šumava")) }.body<Trip>()
        assertEquals(HttpStatusCode.NoContent, client.delete("$base/trips/${empty.id}?version=${empty.version}").status)
    }
}
