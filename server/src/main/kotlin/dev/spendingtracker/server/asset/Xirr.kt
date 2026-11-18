package dev.spendingtracker.server.asset

import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlin.math.abs
import kotlin.math.pow

/** A signed cash flow on [date]: negative for money put in (outflow), positive for money taken out or final worth (inflow). */
data class CashFlow(val date: LocalDate, val amount: Double)

private const val MIN_DAYS = 30
private const val MIN_RATE = -0.99
private const val MAX_RATE = 10.0

/**
 * Annualised money-weighted return (XIRR) over [flows], using a 365-day year.
 * Returns null when the span is under 30 days, there is no sign change in the flows, or the solver does not converge
 * on [-0.99, 10].
 */
fun xirr(flows: List<CashFlow>): Double? {
    val nonZero = flows.filter { it.amount != 0.0 }
    if (nonZero.size < 2) return null
    val hasInflow = nonZero.any { it.amount > 0.0 }
    val hasOutflow = nonZero.any { it.amount < 0.0 }
    if (!hasInflow || !hasOutflow) return null

    val start = nonZero.minOf { it.date }
    val end = nonZero.maxOf { it.date }
    if (start.daysUntil(end) < MIN_DAYS) return null

    val times = nonZero.map { start.daysUntil(it.date) / 365.0 }
    val amounts = nonZero.map { it.amount }
    val scale = amounts.sumOf { abs(it) }.coerceAtLeast(1.0)
    val tol = 1e-6 * scale

    fun npv(r: Double): Double {
        val base = 1.0 + r
        if (base <= 0.0) return Double.NaN
        var sum = 0.0
        for (i in amounts.indices) {
            sum += amounts[i] / base.pow(times[i])
        }
        return sum
    }

    fun dnpv(r: Double): Double {
        val base = 1.0 + r
        if (base <= 0.0) return Double.NaN
        var sum = 0.0
        for (i in amounts.indices) {
            sum -= times[i] * amounts[i] / base.pow(times[i] + 1.0)
        }
        return sum
    }

    // Newton-Raphson first.
    var r = 0.1
    for (iter in 0 until 50) {
        val f = npv(r)
        if (!f.isFinite()) break
        if (abs(f) <= tol && r in MIN_RATE..MAX_RATE) return r
        val df = dnpv(r)
        if (!df.isFinite() || abs(df) < 1e-12) break
        val next = r - f / df
        if (!next.isFinite() || next <= -1.0) break
        if (abs(next - r) < 1e-9 && abs(npv(next)) <= tol && next in MIN_RATE..MAX_RATE) return next
        r = next
    }

    // Fall back to bisection on [-0.99, 10].
    var low = MIN_RATE
    var high = MAX_RATE
    var fLow = npv(low)
    var fHigh = npv(high)
    if (!fLow.isFinite() || !fHigh.isFinite()) return null
    if (abs(fLow) <= tol) return low
    if (abs(fHigh) <= tol) return high
    if (fLow * fHigh > 0.0) return null

    for (iter in 0 until 100) {
        val mid = 0.5 * (low + high)
        val fMid = npv(mid)
        if (!fMid.isFinite()) return null
        if (abs(fMid) <= tol || (high - low) < 1e-9) return mid
        if (fLow * fMid <= 0.0) {
            high = mid
            fHigh = fMid
        } else {
            low = mid
            fLow = fMid
        }
    }
    return 0.5 * (low + high)
}
