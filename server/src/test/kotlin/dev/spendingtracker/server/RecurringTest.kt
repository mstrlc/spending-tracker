package dev.spendingtracker.server

import dev.spendingtracker.server.recurring.Charge
import dev.spendingtracker.server.recurring.detect
import dev.spendingtracker.server.recurring.dueDate
import dev.spendingtracker.server.recurring.schedule
import dev.spendingtracker.shared.api.Expense
import dev.spendingtracker.shared.api.ExpenseInput
import dev.spendingtracker.shared.api.Merchant
import dev.spendingtracker.shared.api.MerchantMerge
import dev.spendingtracker.shared.api.RecurringHealth
import dev.spendingtracker.shared.api.RecurringOverview
import dev.spendingtracker.shared.api.RecurringPayment
import dev.spendingtracker.shared.api.RecurringPaymentInput
import dev.spendingtracker.shared.api.RecurringStatus
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
import kotlin.test.assertNull

class RecurringTest {
    private fun charge(month: Int, day: Int, amount: Long = 299, year: Int = 2026) = Charge(LocalDate(year, month, day), amount)

    @Test
    fun dueDatesKeepTheAnchorDayClampedToShortMonths() {
        val anchor = LocalDate(2026, 1, 31)
        assertEquals(LocalDate(2026, 2, 28), dueDate(anchor, 1, 1))
        assertEquals(LocalDate(2026, 3, 31), dueDate(anchor, 1, 2))
        assertEquals(LocalDate(2027, 1, 31), dueDate(anchor, 12, 1))
    }

    @Test
    fun scheduleFlagsMissedAndChangedCharges() {
        val anchor = LocalDate(2026, 7, 5)
        val paid = listOf(charge(7, 5), charge(8, 4), charge(9, 7), charge(10, 5))
        val ok = schedule(anchor, 1, 299, paid, LocalDate(2026, 10, 20))
        assertEquals(RecurringHealth.OK, ok.health)
        assertEquals(LocalDate(2026, 10, 5) to LocalDate(2026, 11, 5), ok.lastDue to ok.nextDue)
        assertEquals(LocalDate(2026, 10, 5) to 299L, ok.lastChargeDate to ok.lastChargeMinor)

        // Due, but still within the 10 days a charge may come in.
        assertEquals(RecurringHealth.DUE, schedule(anchor, 1, 299, paid, LocalDate(2026, 11, 10)).health)
        val missed = schedule(anchor, 1, 299, paid, LocalDate(2026, 11, 16))
        assertEquals(RecurringHealth.MISSED, missed.health)
        assertEquals(listOf(LocalDate(2026, 11, 5)), missed.missedDates)

        // A charge more than 5% off the expected amount is a change; a few crowns isn't.
        assertEquals(RecurringHealth.CHANGED, schedule(anchor, 1, 299, paid + charge(11, 6, 349), LocalDate(2026, 11, 20)).health)
        assertEquals(RecurringHealth.OK, schedule(anchor, 1, 299, paid + charge(11, 6, 305), LocalDate(2026, 11, 20)).health)

        assertEquals(RecurringHealth.UPCOMING, schedule(LocalDate(2026, 12, 1), 1, 299, emptyList(), LocalDate(2026, 11, 20)).health)
    }

    @Test
    fun detectsMonthlyAndYearlyPayments() {
        val today = LocalDate(2026, 11, 18)
        val netflix = listOf(charge(6, 2), charge(7, 2), charge(8, 3), charge(9, 2), charge(10, 2), charge(11, 2))
        val monthly = detect(netflix, today)!!
        assertEquals(1 to 299L, monthly.intervalMonths to monthly.amountMinor)
        assertEquals(6, monthly.timesSeen)

        // Groceries: every month, but by amounts too different to be one payment.
        val groceries = (6..11).map { charge(it, 10, amount = if (it % 2 == 0) 1_000 else 3_000) }
        assertNull(detect(groceries, today))
        // Stopped: nothing in the last 45 days.
        assertNull(detect(netflix.dropLast(2), today))

        val insurance = listOf(charge(3, 14, 12_000, year = 2025), charge(3, 10, 12_500))
        val yearly = detect(insurance, today)!!
        assertEquals(12 to 12_500L, yearly.intervalMonths to yearly.amountMinor)
        assertEquals(LocalDate(2026, 3, 10), yearly.latest.date)
        // The same merchant charged in between is no yearly payment.
        assertNull(detect(insurance + charge(8, 1, 12_200, year = 2025), today))
    }

    @Test
    fun confirmsSuggestionsAndTracksThem() = apiTest {
        val client = newClient().also { it.signup() }
        val household = "/api/households/${client.createHousehold().id}"
        for (month in 6..11) {
            client.post("$household/expenses") { setBody(ExpenseInput(LocalDate(2026, month, 2), 299, "Netflix")) }
            client.post("$household/expenses") { setBody(ExpenseInput(LocalDate(2026, month, 9), 1_000L * month, "Albert")) }
        }
        val today = "today=2026-11-18"

        val before = client.get("$household/recurring?$today").body<RecurringOverview>()
        val suggestion = before.suggestions.single()
        assertEquals("Netflix" to 299L, suggestion.merchant to suggestion.amountMinor)
        assertEquals(LocalDate(2026, 11, 2), suggestion.anchorDate)

        val confirmed = client.post("$household/recurring?$today") {
            setBody(RecurringPaymentInput(suggestion.merchantId, 299, LocalDate(2026, 6, 2)))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }.body<RecurringPayment>()
        assertEquals(RecurringHealth.OK, confirmed.health)
        assertEquals(LocalDate(2026, 12, 2), confirmed.nextDue)
        client.post("$household/recurring") { setBody(RecurringPaymentInput(suggestion.merchantId, 299, LocalDate(2026, 6, 2))) }
            .assertError(HttpStatusCode.Conflict, "recurring_exists")

        val after = client.get("$household/recurring?$today").body<RecurringOverview>()
        assertEquals(emptyList(), after.suggestions)
        assertEquals(299, after.monthlyMinor)
        // December's charge hasn't come by mid-December: missed.
        val december = client.get("$household/recurring?today=2026-12-15").body<RecurringOverview>().payments.single()
        assertEquals(RecurringHealth.MISSED, december.health)

        // A price rise shows as changed until the expected amount is updated.
        client.post("$household/expenses") { setBody(ExpenseInput(LocalDate(2026, 12, 2), 349, "Netflix")) }
        val risen = client.get("$household/recurring?today=2026-12-15").body<RecurringOverview>().payments.single()
        assertEquals(RecurringHealth.CHANGED to 349L, risen.health to risen.lastChargeMinor)
        val updated = client.put("$household/recurring/${risen.id}?today=2026-12-15") {
            setBody(RecurringPaymentInput(risen.merchantId, 349, risen.anchorDate, version = risen.version))
        }.body<RecurringPayment>()
        assertEquals(RecurringHealth.OK, updated.health)

        // Ending it stops the flags; deleting it makes the merchant a suggestion again.
        val ended = client.put("$household/recurring/${updated.id}?today=2027-02-20") {
            setBody(RecurringPaymentInput(updated.merchantId, 349, updated.anchorDate, status = RecurringStatus.ENDED, version = updated.version))
        }.body<RecurringPayment>()
        assertEquals(RecurringHealth.INACTIVE to emptyList(), ended.health to ended.missedDates)
        client.delete("$household/recurring/${ended.id}?version=${ended.version}").also { assertEquals(HttpStatusCode.NoContent, it.status) }
        assertEquals(listOf("Netflix"), client.get("$household/recurring?$today").body<RecurringOverview>().suggestions.map { it.merchant })

        client.post("$household/recurring") { setBody(RecurringPaymentInput(suggestion.merchantId, 299, LocalDate(2026, 6, 2), intervalMonths = 5)) }
            .assertError(HttpStatusCode.BadRequest, "invalid_interval")
    }

    @Test
    fun dismissingAndMergingMerchants() = apiTest {
        val client = newClient().also { it.signup() }
        val household = "/api/households/${client.createHousehold().id}"
        for (month in 8..11) {
            client.post("$household/expenses") { setBody(ExpenseInput(LocalDate(2026, month, 3), 150, "Spotify")) }
        }
        val other = client.post("$household/expenses") { setBody(ExpenseInput(LocalDate(2026, 1, 3), 150, "Spotify AB")) }.body<Expense>()
        val suggestion = client.get("$household/recurring?today=2026-11-18").body<RecurringOverview>().suggestions.single()
        val dismissed = client.post("$household/recurring") {
            setBody(RecurringPaymentInput(suggestion.merchantId, 150, suggestion.anchorDate, status = RecurringStatus.DISMISSED))
        }.body<RecurringPayment>()
        val overview = client.get("$household/recurring?today=2026-11-18").body<RecurringOverview>()
        assertEquals(emptyList(), overview.suggestions)
        assertEquals(0, overview.monthlyMinor)
        assertEquals(RecurringHealth.INACTIVE, overview.payments.single().health)

        // Merging the merchant into another moves its recurring payment along.
        val source = client.get("$household/merchants").body<List<Merchant>>().single { it.name == "Spotify" }
        client.post("$household/merchants/${source.id}/merge") { setBody(MerchantMerge(other.merchantId!!, source.version)) }
        val moved = client.get("$household/recurring?today=2026-11-18").body<RecurringOverview>().payments.single()
        assertEquals(dismissed.id to "Spotify AB", moved.id to moved.merchant)
    }
}
