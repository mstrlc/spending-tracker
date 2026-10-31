package dev.spendingtracker.shared

import kotlinx.serialization.Serializable

/**
 * An amount in a currency's minor units (cents, haléře), so arithmetic never touches floating point.
 * [currency] is an ISO 4217 code such as "CZK" or "EUR".
 */
@Serializable
data class Money(val amountMinor: Long, val currency: String) {
    init {
        require(currency.length == 3 && currency.all { it in 'A'..'Z' }) { "Invalid currency code: $currency" }
    }

    operator fun plus(other: Money): Money {
        require(currency == other.currency) { "Cannot add $currency and ${other.currency}" }
        return Money(amountMinor + other.amountMinor, currency)
    }

    operator fun minus(other: Money): Money {
        require(currency == other.currency) { "Cannot subtract ${other.currency} from $currency" }
        return Money(amountMinor - other.amountMinor, currency)
    }
}
