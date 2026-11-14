package dev.spendingtracker.server

import dev.spendingtracker.server.auth.AUTH_RATE_LIMIT
import dev.spendingtracker.server.auth.SESSION_AUTH
import dev.spendingtracker.server.auth.UserPrincipal
import dev.spendingtracker.server.auth.authRoutes
import dev.spendingtracker.server.auth.sessionCookie
import dev.spendingtracker.server.bank.BankSetup
import dev.spendingtracker.server.bank.BankSync
import dev.spendingtracker.server.bank.GoCardlessBankData
import dev.spendingtracker.server.bank.bankSetup
import dev.spendingtracker.server.bank.nextPragueRunDelay
import dev.spendingtracker.server.common.apiErrors
import dev.spendingtracker.server.db.AuditActor
import dev.spendingtracker.server.db.connectDatabase
import dev.spendingtracker.server.household.householdRoutes
import dev.spendingtracker.server.household.inviteLinkRoutes
import dev.spendingtracker.server.market.LiveMarketData
import dev.spendingtracker.server.market.Market
import dev.spendingtracker.server.market.MarketData
import dev.spendingtracker.server.market.market
import dev.spendingtracker.server.market.securityRoutes
import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.config.SchemaGenerator
import io.github.smiley4.ktoropenapi.openApi
import io.github.smiley4.ktorswaggerui.swaggerUI
import io.github.smiley4.schemakenerator.swagger.data.RefType
import io.github.smiley4.schemakenerator.swagger.data.TitleType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitRouteSelector
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import javax.sql.DataSource
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

private val appLog = LoggerFactory.getLogger("Application")

fun main() {
    val config = Config.fromEnv()
    val dataSource = connectDatabase(config.db)
    val bankSetup = config.goCardless?.let { BankSetup(GoCardlessBankData(it.secretId, it.secretKey), config.appUrl) }
    embeddedServer(Netty, port = config.port) {
        module(dataSource, config.secureCookies, bankSetup = bankSetup, bankSyncHour = config.bankSyncHour)
    }.start(wait = true)
}

val apiJson = Json { ignoreUnknownKeys = true }

fun Application.module(
    dataSource: DataSource,
    secureCookies: Boolean = false,
    marketData: MarketData = LiveMarketData(),
    bankSetup: BankSetup? = null,
    bankSyncHour: Int? = null,
) {
    market = Market(marketData)
    this.bankSetup = bankSetup
    if (bankSetup != null && bankSyncHour != null) {
        launch {
            while (isActive) {
                delay(nextPragueRunDelay(bankSyncHour, Clock.System.now()))
                try {
                    val results = withContext(AuditActor { null }) { BankSync(bankSetup.data, market).syncAll() }
                    appLog.info("Daily bank sync finished across {} feeds", results.size)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    appLog.warn("Daily bank sync failed", e)
                }
            }
        }
    }
    install(CallLogging)
    install(ContentNegotiation) { json(apiJson) }
    install(StatusPages) { apiErrors() }
    install(Authentication) { sessionCookie(secureCookies) }
    install(RateLimit) {
        register(AUTH_RATE_LIMIT) {
            rateLimiter(limit = 20, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteHost }
        }
    }
    install(OpenApi) {
        // rateLimit { } adds a route segment that isn't part of the URL.
        ignoredRouteSelectors = ignoredRouteSelectors + RateLimitRouteSelector::class
        info {
            title = "Spending Tracker API"
            version = "0.1.0"
        }
        schemas {
            // Generate schemas from the same serializers the API uses, named "Health"
            // rather than "dev.spendingtracker.server.Health" so the generated TS types stay readable.
            generator = SchemaGenerator.kotlinx(apiJson) {
                title = TitleType.SIMPLE
                referencePath = RefType.OPENAPI_SIMPLE
            }
        }
    }

    routing {
        route("/api") {
            intercept(ApplicationCallPipeline.Call) {
                val call = context
                withContext(AuditActor { call.principal<UserPrincipal>()?.userId }) { proceed() }
            }
            route("/openapi.json") { openApi() }
            route("/docs") { swaggerUI("/api/openapi.json") }
            healthRoutes(dataSource)
            authRoutes(secureCookies)
            inviteLinkRoutes()
            authenticate(SESSION_AUTH) {
                householdRoutes()
                securityRoutes()
            }
        }
    }
}
