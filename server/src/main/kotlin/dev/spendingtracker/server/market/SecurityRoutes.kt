package dev.spendingtracker.server.market

import dev.spendingtracker.server.asset.loadPrices
import dev.spendingtracker.server.common.ApiException
import dev.spendingtracker.server.common.badRequest
import dev.spendingtracker.server.db.dbQuery
import dev.spendingtracker.shared.api.ApiError
import dev.spendingtracker.shared.api.FxRateQuote
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.datetime.LocalDate
import dev.spendingtracker.shared.api.SecurityMatch as SecurityMatchDto

/** Needs an authenticated user; securities aren't household data. */
fun Route.securityRoutes() = route("/securities") {
    get("/search", {
        operationId = "searchSecurities"
        description = "Stocks, ETFs and funds matching a name, ticker or ISIN, for a holding to follow."
        request {
            queryParameter<String>("q") { required = true }
        }
        response {
            code(HttpStatusCode.OK) { body<List<SecurityMatchDto>>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.ServiceUnavailable) { body<ApiError>() }
        }
    }) {
        val query = call.request.queryParameters["q"]?.trim().orEmpty()
        if (query.length !in 1..100) throw badRequest("invalid_query", "q must be 1–100 characters", "q")
        val matches = try {
            call.application.market.search(query)
        } catch (e: Exception) {
            throw ApiException(HttpStatusCode.ServiceUnavailable, "market_unavailable", "Couldn't reach the market data provider. Try again later.")
        }
        call.respond(matches.map { SecurityMatchDto(it.symbol, it.name, it.exchange, it.type) })
    }

    get("/fx", {
        operationId = "getFxRate"
        description = "ČNB exchange rate for 1 unit of [from] in [to] on or before [date]."
        request {
            queryParameter<String>("from") { required = true }
            queryParameter<String>("to") { required = true }
            queryParameter<String>("date") { required = true }
        }
        response {
            code(HttpStatusCode.OK) { body<FxRateQuote>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
        }
    }) {
        val from = call.request.queryParameters["from"]?.trim()?.uppercase().orEmpty()
        val to = call.request.queryParameters["to"]?.trim()?.uppercase().orEmpty()
        val dateText = call.request.queryParameters["date"]?.trim().orEmpty()
        if (!Regex("^[A-Z]{3}$").matches(from)) throw badRequest("invalid_currency", "Invalid currency", "from")
        if (!Regex("^[A-Z]{3}$").matches(to)) throw badRequest("invalid_currency", "Invalid currency", "to")
        val date = runCatching { LocalDate.parse(dateText) }.getOrNull()
            ?: throw badRequest("invalid_date", "Invalid date", "date")
        if (from != to) {
            call.application.market.refreshFx(date)
        }
        val rate = dbQuery {
            loadPrices(to, emptyList(), listOf(from)).rate(from, date)?.toDouble()
        }
        call.respond(FxRateQuote(from, to, date, rate))
    }
}
