package dev.spendingtracker.server.market

import dev.spendingtracker.server.common.ApiException
import dev.spendingtracker.server.common.badRequest
import dev.spendingtracker.shared.api.ApiError
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
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
}
