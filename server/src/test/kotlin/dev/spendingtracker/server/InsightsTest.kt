package dev.spendingtracker.server

import dev.spendingtracker.server.overview.Spend
import dev.spendingtracker.server.overview.anomalyMonth
import dev.spendingtracker.server.overview.spendingAnomalies
import dev.spendingtracker.server.overview.subscriptions
import dev.spendingtracker.server.overview.typicalMonth
import dev.spendingtracker.shared.api.MonthFlow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class InsightsTest {
    private val food = Uuid.random()
    private val travel = Uuid.random()

    private fun spend(month: Int, amount: Long, merchant: String = "", category: Uuid? = food, day: Int = 5, year: Int = 2026) =
        Spend(LocalDate(year, month, day), amount, merchant, category, investment = false)

    @Test
    fun typicalMonthDropsUnusuallyExpensiveMonthsWhole() {
        val months = listOf(10_000L, 11_000, 9_000, 10_500, 60_000, 9_500).mapIndexed { i, spent ->
            MonthFlow("2026-0${i + 1}", incomeMinor = 20_000, spendingMinor = spent, investedMinor = 1_000)
        }
        val typical = typicalMonth(months)!!
        assertEquals(listOf("2026-05"), typical.outlierMonths)
        assertEquals(5, typical.monthCount)
        assertEquals(10_000, typical.spendingMinor)
        assertEquals(20_000, typical.incomeMinor)
    }

    @Test
    fun typicalMonthNeedsFourMonthsBeforeTrimming() {
        val months = listOf(1_000L, 50_000).mapIndexed { i, spent -> MonthFlow("2026-0${i + 1}", 0, spent, 0) }
        assertEquals(emptyList(), typicalMonth(months)!!.outlierMonths)
        assertEquals(null, typicalMonth(emptyList()))
    }

    @Test
    fun anomaliesCompareAgainstZeroFilledBaselineScaledToTheMonthSoFar() {
        // November 2025 to October 2026: food 10 000 every month; travel 12 000 twice → its usual month is 2 000.
        val history = (11..12).map { spend(it, 10_000, year = 2025) } + (1..10).map { spend(it, 10_000) } +
            spend(3, 12_000, category = travel) + spend(7, 12_000, category = travel)
        val november = listOf(spend(11, 10_000, day = 14), spend(11, 9_000, category = travel, day = 14))
        val today = LocalDate(2026, 11, 15)

        val anomalies = spendingAnomalies(history + november, YearMonth(2026, 11), YearMonth(2025, 11), today)

        // Half the month in: food at 10 000 is twice its 5 000 pace; travel at 9 000 is 9× its 1 000 pace.
        assertEquals(listOf(travel.toString(), food.toString()), anomalies.map { it.categoryId })
        assertEquals(1_000, anomalies[0].baselineMinor)
        assertEquals(9.0, anomalies[0].ratio, 0.01)
    }

    @Test
    fun anomaliesSkipCategoriesSeenInFewerThanTwoMonths() {
        val rows = listOf(spend(8, 10_000), spend(9, 500, category = travel), spend(10, 5_000, category = travel))
        assertEquals(emptyList(), spendingAnomalies(rows, YearMonth(2026, 10), YearMonth(2026, 8), LocalDate(2026, 11, 1)))
    }

    @Test
    fun anomaliesLookAtTheMonthBeforeEarlyInAMonth() {
        assertEquals(YearMonth(2026, 9), anomalyMonth(YearMonth(2026, 10), LocalDate(2026, 10, 9)))
        assertEquals(YearMonth(2026, 10), anomalyMonth(YearMonth(2026, 10), LocalDate(2026, 10, 10)))
        assertEquals(YearMonth(2026, 8), anomalyMonth(YearMonth(2026, 8), LocalDate(2026, 10, 1)))
    }

    @Test
    fun subscriptionsAreSteadyMonthlyMerchants() {
        val rows = (5..10).map { spend(it, 29_900, "Netflix") } +
            // Billed twice a month: summed per month, not averaged per charge.
            (7..10).flatMap { listOf(spend(it, 500, "gym", day = 1), spend(it, 500, "Gym", day = 15)) } +
            // Same merchant every month, but the amounts swing too much to be a subscription.
            (6..9).map { spend(it, it * 10_000L, "Albert") } +
            listOf(spend(9, 1_000, "Spotify"), spend(10, 1_000, "Spotify"))

        val found = subscriptions(rows, YearMonth(2026, 10))
        assertEquals(listOf("Netflix" to 29_900L, "Gym" to 1_000L), found.map { it.merchant to it.monthlyMinor })
        assertEquals(listOf(6, 4), found.map { it.monthsSeen })
    }
}
