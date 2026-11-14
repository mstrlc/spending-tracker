package dev.spendingtracker.server

import dev.spendingtracker.server.asset.Dated
import dev.spendingtracker.server.asset.standing
import dev.spendingtracker.shared.api.Account
import dev.spendingtracker.shared.api.AccountInput
import dev.spendingtracker.shared.api.ActivityEntity
import dev.spendingtracker.shared.api.ActivityPage
import dev.spendingtracker.shared.api.Contribution
import dev.spendingtracker.shared.api.ContributionInput
import dev.spendingtracker.shared.api.Fee
import dev.spendingtracker.shared.api.FeeInput
import dev.spendingtracker.shared.api.Asset
import dev.spendingtracker.shared.api.AssetInput
import dev.spendingtracker.shared.api.AssetKind
import dev.spendingtracker.shared.api.Valuation
import dev.spendingtracker.shared.api.ValuationInput
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
import kotlin.test.assertTrue

class AssetTest {
    @Test
    fun valueIsTheLatestValuationPlusLaterContributions() {
        val contributions = listOf(Dated(LocalDate(2026, 1, 15), 10_000), Dated(LocalDate(2026, 6, 30), 5_000), Dated(LocalDate(2026, 9, 1), 2_000))
        val valuations = listOf(Dated(LocalDate(2026, 3, 31), 9_000), Dated(LocalDate(2026, 6, 30), 16_000))

        val standing = standing(contributions, valuations)
        assertEquals(17_000, standing.contributedMinor)
        // The June valuation already includes that day's contribution; September's is added at cost.
        assertEquals(18_000, standing.valueMinor)
        assertEquals(1_000, standing.gainMinor)
        assertEquals(LocalDate(2026, 6, 30), standing.valuedOn)
        assertEquals(LocalDate(2026, 1, 15), standing.firstContributedOn)

        val unvalued = standing(contributions, emptyList())
        assertEquals(17_000L to 0L, unvalued.valueMinor to unvalued.gainMinor)
        assertEquals(null, unvalued.valuedOn)
    }

    @Test
    fun assetsBeyondMarketHoldingsAreValuedByHand() = apiTest {
        val client = newClient()
        val me = client.signup()
        val household = "/api/households/${client.createHousehold().id}"

        val car = client.post("$household/assets") { setBody(AssetInput("Octavia", kind = AssetKind.VEHICLE)) }.body<Asset>()
        client.post("$household/assets/${car.id}/valuations") { setBody(ValuationInput(LocalDate(2026, 6, 30), 250_000)) }
        val savings = client.post("$household/assets") { setBody(AssetInput("Building savings", kind = AssetKind.SCHEME, ownerUserId = me.id)) }
            .body<Asset>()

        val listed = client.get("$household/assets").body<List<Asset>>().associateBy { it.id }
        assertEquals(Triple(AssetKind.VEHICLE, false, 250_000L), listed.getValue(car.id).let { Triple(it.kind, it.marketValued, it.valueMinor) })
        assertEquals(AssetKind.SCHEME to me.id, listed.getValue(savings.id).let { it.kind to it.ownerUserId })
    }

    @Test
    fun holdingsShowContributionsValuationsAndGain() = apiTest {
        val client = newClient()
        val me = client.signup()
        val household = "/api/households/${client.createHousehold().id}"
        val bank = client.post("$household/accounts") { setBody(AccountInput("Bank")) }.body<Account>()

        val fund = client.post("$household/assets") { setBody(AssetInput(" World ETF ", ownerUserId = me.id)) }.body<Asset>()
        assertEquals("World ETF", fund.name)
        assertEquals(AssetKind.FUND, fund.kind)
        assertEquals(0L to null, fund.contributedMinor to fund.valuedOn)

        val contributions = "$household/assets/${fund.id}/contributions"
        val first = client.post(contributions) { setBody(ContributionInput(LocalDate(2026, 1, 15), 100_000, accountId = bank.id)) }.body<Contribution>()
        client.post(contributions) { setBody(ContributionInput(LocalDate(2026, 8, 1), 50_000)) }
        client.post(contributions) { setBody(ContributionInput(LocalDate(2026, 9, 1), -20_000, notes = "rebalance")) }
        val valuations = "$household/assets/${fund.id}/valuations"
        client.post(valuations) { setBody(ValuationInput(LocalDate(2026, 6, 30), 112_000)) }

        val listed = client.get("$household/assets").body<List<Asset>>().single()
        assertEquals(130_000, listed.contributedMinor)
        assertEquals(142_000, listed.valueMinor)
        assertEquals(12_000, listed.gainMinor)
        assertEquals(LocalDate(2026, 6, 30), listed.valuedOn)
        assertEquals(LocalDate(2026, 1, 15), listed.firstContributedOn)
        assertEquals(3, client.get(contributions).body<List<Contribution>>().size)

        client.post(valuations) { setBody(ValuationInput(LocalDate(2026, 6, 30), 1)) }
            .assertError(HttpStatusCode.Conflict, "valuation_exists")
        client.post(valuations) { setBody(ValuationInput(LocalDate(2026, 7, 1), -1)) }
            .assertError(HttpStatusCode.BadRequest, "invalid_amount")
        client.post(contributions) { setBody(ContributionInput(LocalDate(2026, 7, 1), 0)) }
            .assertError(HttpStatusCode.BadRequest, "invalid_amount")
        client.delete("$household/accounts/${bank.id}?version=1").assertError(HttpStatusCode.Conflict, "account_in_use")

        val edited = client.put("$contributions/${first.id}") {
            setBody(ContributionInput(LocalDate(2026, 1, 15), 90_000, version = 1))
        }.body<Contribution>()
        assertEquals(2, edited.version)
        client.put("$contributions/${first.id}") { setBody(ContributionInput(LocalDate(2026, 1, 15), 1, version = 1)) }
            .assertError(HttpStatusCode.Conflict, "version_conflict")

        val latest = client.post(valuations) { setBody(ValuationInput(LocalDate(2026, 10, 31), 150_000)) }.body<Valuation>()
        val now = client.get("$household/assets").body<List<Asset>>().single()
        assertEquals(30_000, now.gainMinor)
        assertEquals(HttpStatusCode.NoContent, client.delete("$valuations/${latest.id}?version=1").status)

        assertEquals(HttpStatusCode.NoContent, client.delete("$household/assets/${fund.id}?version=1").status)
        assertTrue(client.get("$household/assets").body<List<Asset>>().isEmpty())
        client.get(contributions).assertError(HttpStatusCode.NotFound, "not_found")
    }

    @Test
    fun householdsCannotReachEachOthersHoldings() = apiTest {
        val alice = newClient().also { it.signup("alice@example.com") }
        val bob = newClient()
        val bobMe = bob.signup("bob@example.com", "Bob")
        val aliceHome = "/api/households/${alice.createHousehold().id}"
        val bobHome = "/api/households/${bob.createHousehold().id}"
        val bobsFund = bob.post("$bobHome/assets") { setBody(AssetInput("Bob's fund")) }.body<Asset>()

        alice.post("$aliceHome/assets") { setBody(AssetInput("Fund", ownerUserId = bobMe.id)) }
            .assertError(HttpStatusCode.BadRequest, "invalid_member")
        alice.post("$aliceHome/assets") { setBody(AssetInput(" ")) }
            .assertError(HttpStatusCode.BadRequest, "required")
        alice.post("$aliceHome/assets/${bobsFund.id}/contributions") { setBody(ContributionInput(LocalDate(2026, 10, 1), 100)) }
            .assertError(HttpStatusCode.NotFound, "not_found")
        alice.get("$aliceHome/assets/${bobsFund.id}/valuations").assertError(HttpStatusCode.NotFound, "not_found")
    }

    @Test
    fun contributionsAreLoggedUnderTheirHolding() = apiTest {
        val client = newClient().also { it.signup() }
        val household = "/api/households/${client.createHousehold().id}"
        val fund = client.post("$household/assets") { setBody(AssetInput("World ETF")) }.body<Asset>()
        client.post("$household/assets/${fund.id}/contributions") { setBody(ContributionInput(LocalDate(2026, 10, 1), 100)) }

        val entry = client.get("$household/activity?entity=asset_contribution").body<ActivityPage>().entries.single()
        assertEquals(ActivityEntity.ASSET_CONTRIBUTION, entry.entity)
        assertEquals("World ETF", entry.label)
        assertEquals(listOf("date", "amountMinor", "quantity", "accountId", "notes"), entry.changes.map { it.field })
    }

    @Test
    fun feesAreTotalledWithoutTouchingWorthOrMoneyPutIn() = apiTest {
        val client = newClient().also { it.signup() }
        val household = "/api/households/${client.createHousehold().id}"
        val fund = client.post("$household/assets") { setBody(AssetInput("Portu")) }.body<Asset>()
        client.post("$household/assets/${fund.id}/contributions") { setBody(ContributionInput(LocalDate(2026, 1, 5), 100_000)) }
        val fees = "$household/assets/${fund.id}/fees"
        val fee = client.post(fees) { setBody(FeeInput(LocalDate(2026, 2, 4), 9_000, "Správcovský poplatek")) }.body<Fee>()
        // A discount comes off.
        client.post(fees) { setBody(FeeInput(LocalDate(2026, 2, 4), -4_000, "Sleva na poplatku")) }
        client.post(fees) { setBody(FeeInput(LocalDate(2026, 2, 4), 0)) }.assertError(HttpStatusCode.BadRequest, "invalid_amount")

        val held = client.get("$household/assets").body<List<Asset>>().single()
        assertEquals(5_000, held.feesMinor)
        assertEquals(100_000L to 100_000L, held.contributedMinor to held.valueMinor)
        assertEquals(2, client.get(fees).body<List<Fee>>().size)

        client.put("$fees/${fee.id}") { setBody(FeeInput(LocalDate(2026, 2, 4), 8_000, version = 1)) }
        assertEquals(4_000, client.get("$household/assets").body<List<Asset>>().single().feesMinor)
        assertEquals(HttpStatusCode.NoContent, client.delete("$fees/${fee.id}?version=2").status)
    }
}
