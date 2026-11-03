package dev.spendingtracker.server.market

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.time.Instant

/**
 * Prices and search from Yahoo Finance's public (unofficial, keyless) endpoints; exchange rates from the ČNB API.
 * Yahoo can change or throttle these without notice, which is why everything goes through [MarketData].
 */
class LiveMarketData : MarketData {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: String): List<SecurityMatch> {
        val q = URLEncoder.encode(query, Charsets.UTF_8)
        val body = get("https://query1.finance.yahoo.com/v1/finance/search?q=$q&quotesCount=10&newsCount=0") ?: return emptyList()
        return body["quotes"]?.jsonArray.orEmpty().map { it.jsonObject }
            .filter { it.text("quoteType") in SEARCHABLE_TYPES && it.text("symbol") != null }
            .map {
                SecurityMatch(
                    symbol = it.text("symbol")!!,
                    name = it.text("longname") ?: it.text("shortname") ?: it.text("symbol")!!,
                    exchange = it.text("exchDisp") ?: it.text("exchange").orEmpty(),
                    type = it.text("typeDisp") ?: it.text("quoteType").orEmpty(),
                )
            }
    }

    override suspend fun history(symbol: String, from: LocalDate?): SecurityHistory? {
        val s = URLEncoder.encode(symbol, Charsets.UTF_8)
        val range = if (from == null) {
            "range=max"
        } else {
            "period1=${from.atStartOfDayIn(TimeZone.UTC).epochSeconds}&period2=${kotlin.time.Clock.System.now().epochSeconds}"
        }
        val body = get("https://query1.finance.yahoo.com/v8/finance/chart/$s?$range&interval=1d") ?: return null
        val result = body["chart"]?.jsonObject?.get("result")?.takeIf { it != JsonNull }?.jsonArray?.firstOrNull()?.jsonObject
            ?: return null
        val meta = result["meta"]!!.jsonObject
        // Timestamps are the session's open; the exchange's own time zone gives the trading day.
        val zone = meta.text("exchangeTimezoneName")?.let { runCatching { TimeZone.of(it) }.getOrNull() } ?: TimeZone.UTC
        val timestamps = result["timestamp"]?.jsonArray.orEmpty().map { it.jsonPrimitive.long }
        val closes = result["indicators"]?.jsonObject?.get("quote")?.jsonArray?.firstOrNull()?.jsonObject?.get("close")?.jsonArray.orEmpty()
        val daily = timestamps.zip(closes).mapNotNull { (ts, close) ->
            val value = close.takeIf { it != JsonNull }?.jsonPrimitive?.content?.toBigDecimalOrNull() ?: return@mapNotNull null
            DailyClose(Instant.fromEpochSeconds(ts).toLocalDateTime(zone).date, value)
        }.associateBy { it.date }.values.toList() // The day in progress can appear twice.
        val info = SecurityInfo(
            symbol = meta.text("symbol") ?: symbol,
            name = meta.text("longName") ?: meta.text("shortName") ?: symbol,
            exchange = meta.text("fullExchangeName") ?: meta.text("exchangeName").orEmpty(),
            currency = meta.text("currency") ?: return null,
        )
        return SecurityHistory(info, daily)
    }

    override suspend fun czkRates(year: Int): List<CzkRate> {
        val body = get("https://api.cnb.cz/cnbapi/exrates/daily-year?year=$year&lang=EN") ?: return emptyList()
        return body["rates"]?.jsonArray.orEmpty().map { it.jsonObject }.map {
            val amount = BigDecimal(it.text("amount")!!)
            CzkRate(it.text("currencyCode")!!, LocalDate.parse(it.text("validFor")!!), BigDecimal(it.text("rate")!!).divide(amount))
        }
    }

    /** The JSON body of a successful GET; null for 404 and the like, an exception for network failures. */
    private suspend fun get(url: String): JsonObject? = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(15))
            // Yahoo turns away requests without a browser-like user agent.
            .header("User-Agent", "Mozilla/5.0 (compatible; spending-tracker)")
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        when {
            response.statusCode() in 200..299 -> json.parseToJsonElement(response.body()).jsonObject
            response.statusCode() in 400..499 && response.statusCode() != 429 -> null
            else -> error("GET $url failed with ${response.statusCode()}")
        }
    }

    private fun JsonObject.text(key: String): String? = this[key]?.takeIf { it != JsonNull }?.jsonPrimitive?.content

    private companion object {
        val SEARCHABLE_TYPES = setOf("EQUITY", "ETF", "MUTUALFUND", "CRYPTOCURRENCY")
    }
}
