package dev.spendingtracker.server

import dev.spendingtracker.shared.api.Account
import dev.spendingtracker.shared.api.AccountKind
import dev.spendingtracker.shared.api.Asset
import dev.spendingtracker.shared.api.AssetKind
import dev.spendingtracker.shared.api.BankFeed
import dev.spendingtracker.shared.api.BankFeedInput
import dev.spendingtracker.shared.api.BankInstitution
import dev.spendingtracker.shared.api.BankRow
import dev.spendingtracker.shared.api.BankRowOutcome
import dev.spendingtracker.shared.api.BankRowRef
import dev.spendingtracker.shared.api.Category
import dev.spendingtracker.shared.api.CategoryKind
import dev.spendingtracker.shared.api.CreateInviteRequest
import dev.spendingtracker.shared.api.Expense
import dev.spendingtracker.shared.api.ExpenseInput
import dev.spendingtracker.shared.api.HouseholdImport
import dev.spendingtracker.shared.api.ImportAccount
import dev.spendingtracker.shared.api.ImportAsset
import dev.spendingtracker.shared.api.ImportBankFeed
import dev.spendingtracker.shared.api.ImportBankRow
import dev.spendingtracker.shared.api.ImportCategory
import dev.spendingtracker.shared.api.ImportContribution
import dev.spendingtracker.shared.api.ImportCounts
import dev.spendingtracker.shared.api.ImportExpense
import dev.spendingtracker.shared.api.ImportIncome
import dev.spendingtracker.shared.api.ImportPerson
import dev.spendingtracker.shared.api.ImportResult
import dev.spendingtracker.shared.api.ImportSummary
import dev.spendingtracker.shared.api.ImportTrip
import dev.spendingtracker.shared.api.ImportUndoResult
import dev.spendingtracker.shared.api.Income
import dev.spendingtracker.shared.api.IncomeKind
import dev.spendingtracker.shared.api.Invite
import dev.spendingtracker.shared.api.LinkBankRows
import dev.spendingtracker.shared.api.Member
import dev.spendingtracker.shared.api.Merchant
import dev.spendingtracker.shared.api.StatementUpload
import dev.spendingtracker.shared.api.Trip
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportUndoTest {
    private val sampleRaw = Json.parseToJsonElement(
        """
        {
          "transactionId": "tx-import-1",
          "bookingDate": "2026-08-05",
          "transactionAmount": { "amount": "-250.00", "currency": "CZK" },
          "creditorName": "Billa",
          "remittanceInformationUnstructured": "CARD BILLA"
        }
        """.trimIndent(),
    ).jsonObject

    private val file = HouseholdImport(
        currency = "CZK",
        people = listOf(ImportPerson("Alice", email = "alice@example.com"), ImportPerson("Bob")),
        accounts = listOf(
            ImportAccount("Joint"),
            ImportAccount("Bob's card", AccountKind.CREDIT_CARD, owner = "Bob"),
        ),
        categories = listOf(
            ImportCategory("Gas", parent = "Car"),
            ImportCategory("Car"),
            ImportCategory("Groceries"), // already exists on new household
        ),
        trips = listOf(ImportTrip("Alps", LocalDate(2026, 8, 1), LocalDate(2026, 8, 9))),
        expenses = listOf(
            ImportExpense("ref:e1", LocalDate(2026, 8, 2), 1_200_00, "OMV", "Car", "Gas", "Bob's card", "Bob", trip = "Alps"),
            ImportExpense("ref:e2", LocalDate(2026, 8, 3), 300_00, "omv", "Groceries", account = "Joint"),
        ),
        income = listOf(
            ImportIncome("ref:i1", LocalDate(2026, 9, 1), 50_000_00, "Employer", "2026-08", recipient = "Bob"),
            ImportIncome("ref:i2", LocalDate(2026, 8, 8), 3_000_00, "ZPMV", recipient = "Bob", kind = IncomeKind.DEDUCTION),
        ),
        assets = listOf(
            ImportAsset(
                "Pension · Bob", AssetKind.SCHEME, owner = "Bob",
                contributions = listOf(ImportContribution(LocalDate(2026, 8, 16), 1_700_00, account = "Joint")),
            ),
        ),
        bankFeeds = listOf(ImportBankFeed("Joint feed", "Joint", "CZK", BankInstitution.OTHER)),
        bankRows = listOf(ImportBankRow("Joint feed", sampleRaw, expense = "ref:e2")),
    )

    @Test
    fun undoRemovesWhatImportCreatedAndNothingMatched() = apiTest {
        val alice = newClient().also { it.signup() }
        val home = alice.createHousehold()
        val base = "/api/households/${home.id}"

        // Dry run import leaves no imports row.
        alice.post("$base/import?dryRun=true") { setBody(file) }.body<ImportResult>()
        assertEquals(emptyList(), alice.get("$base/imports").body<List<ImportSummary>>())

        val existingCategories = alice.get("$base/categories").body<List<Category>>().map { it.id }.toSet()

        val result = alice.post("$base/import") { setBody(file) }.body<ImportResult>()
        val imports = alice.get("$base/imports").body<List<ImportSummary>>()
        assertEquals(1, imports.size)
        val imp = imports.single()
        assertEquals(result.created, imp.counts)
        assertNull(imp.undoneAt)
        assertEquals("Alice", imp.createdByName)

        // Dry run undo reports everything and changes nothing.
        val dryUndo = alice.post("$base/imports/${imp.id}/undo?dryRun=true").body<ImportUndoResult>()
        assertTrue(dryUndo.dryRun)
        assertEquals(result.created, dryUndo.deleted)
        assertEquals(ImportCounts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), dryUndo.kept)
        assertEquals(2, alice.get("$base/expenses").body<List<Expense>>().size)
        assertNull(alice.get("$base/imports").body<List<ImportSummary>>().single().undoneAt)

        // Real undo removes everything the import created, leaving pre-existing categories and Alice.
        val undo = alice.post("$base/imports/${imp.id}/undo").body<ImportUndoResult>()
        assertEquals(false, undo.dryRun)
        assertEquals(result.created, undo.deleted)
        assertEquals(emptyList(), alice.get("$base/expenses").body<List<Expense>>())
        assertEquals(emptyList(), alice.get("$base/income").body<List<Income>>())
        assertEquals(emptyList(), alice.get("$base/assets").body<List<Asset>>())
        assertEquals(emptyList(), alice.get("$base/trips").body<List<Trip>>())
        assertEquals(emptyList(), alice.get("$base/merchants").body<List<Merchant>>())
        assertEquals(emptyList(), alice.get("$base/accounts").body<List<Account>>())
        assertEquals(emptyList(), alice.get("$base/bank-feeds").body<List<BankFeed>>())
        assertEquals(existingCategories, alice.get("$base/categories").body<List<Category>>().map { it.id }.toSet())
        assertEquals(1, alice.get("$base/members").body<List<Member>>().size)

        val updated = alice.get("$base/imports").body<List<ImportSummary>>().single()
        assertNotNull(updated.undoneAt)
        assertEquals("Alice", updated.undoneByName)

        // Second undo is 409 import_already_undone.
        alice.post("$base/imports/${imp.id}/undo").assertError(HttpStatusCode.Conflict, "import_already_undone")
    }

    @Test
    fun undoRemovesWhatAutoRulesMadeFromTheImportsRows() = apiTest {
        val alice = newClient().also { it.signup() }
        val base = "/api/households/${alice.createHousehold().id}"
        alice.post("$base/inbox-rules") {
            setBody(
                dev.spendingtracker.shared.api.InboxRuleInput(
                    name = "Billa", textContains = "BILLA", outcome = dev.spendingtracker.shared.api.RuleOutcome.EXPENSE,
                    merchant = "Billa", auto = true,
                ),
            )
        }.also { assertEquals(HttpStatusCode.Created, it.status) }

        alice.post("$base/import") {
            setBody(
                HouseholdImport(
                    currency = "CZK",
                    accounts = listOf(ImportAccount("Joint")),
                    bankFeeds = listOf(ImportBankFeed("Joint feed", "Joint", "CZK", BankInstitution.OTHER)),
                    bankRows = listOf(ImportBankRow("Joint feed", sampleRaw)),
                ),
            )
        }.body<ImportResult>()
        // The rule filed the imported row as an expense the import itself didn't create.
        assertEquals(1, alice.get("$base/expenses").body<List<Expense>>().size)

        val imp = alice.get("$base/imports").body<List<ImportSummary>>().single()
        val undo = alice.post("$base/imports/${imp.id}/undo").body<ImportUndoResult>()
        assertEquals(1, undo.deleted.expenses)
        assertEquals(1, undo.deleted.merchants)
        assertEquals(emptyList(), alice.get("$base/expenses").body<List<Expense>>())
        assertEquals(emptyList(), alice.get("$base/merchants").body<List<Merchant>>())
        assertEquals(emptyList(), alice.get("$base/accounts").body<List<Account>>())
    }

    @Test
    fun undoRemovesRuleMadeMerchantAndKeepsPreExistingMerchant() = apiTest {
        val alice = newClient().also { it.signup() }
        val base = "/api/households/${alice.createHousehold().id}"
        alice.post("$base/merchants") {
            setBody(dev.spendingtracker.shared.api.MerchantInput("Albert"))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
        alice.post("$base/inbox-rules") {
            setBody(
                dev.spendingtracker.shared.api.InboxRuleInput(
                    name = "Billa", textContains = "BILLA", outcome = dev.spendingtracker.shared.api.RuleOutcome.EXPENSE,
                    merchant = "Billa", auto = true,
                ),
            )
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
        alice.post("$base/inbox-rules") {
            setBody(
                dev.spendingtracker.shared.api.InboxRuleInput(
                    name = "Albert", textContains = "ALBERT", outcome = dev.spendingtracker.shared.api.RuleOutcome.EXPENSE,
                    merchant = "Albert", auto = true,
                ),
            )
        }.also { assertEquals(HttpStatusCode.Created, it.status) }

        val albertRaw = Json.parseToJsonElement(
            """
            {
              "transactionId": "tx-import-2",
              "bookingDate": "2026-08-06",
              "transactionAmount": { "amount": "-180.00", "currency": "CZK" },
              "creditorName": "Albert",
              "remittanceInformationUnstructured": "CARD ALBERT"
            }
            """.trimIndent(),
        ).jsonObject

        alice.post("$base/import") {
            setBody(
                HouseholdImport(
                    currency = "CZK",
                    accounts = listOf(ImportAccount("Joint")),
                    bankFeeds = listOf(ImportBankFeed("Joint feed", "Joint", "CZK", BankInstitution.OTHER)),
                    bankRows = listOf(
                        ImportBankRow("Joint feed", sampleRaw),
                        ImportBankRow("Joint feed", albertRaw),
                    ),
                ),
            )
        }.body<ImportResult>()
        assertEquals(2, alice.get("$base/expenses").body<List<Expense>>().size)
        assertEquals(setOf("Albert", "Billa"), alice.get("$base/merchants").body<List<Merchant>>().map { it.name }.toSet())

        val imp = alice.get("$base/imports").body<List<ImportSummary>>().single()
        val undo = alice.post("$base/imports/${imp.id}/undo").body<ImportUndoResult>()
        assertEquals(2, undo.deleted.expenses)
        assertEquals(1, undo.deleted.merchants)
        assertEquals(0, undo.kept.merchants)
        assertEquals(emptyList(), alice.get("$base/expenses").body<List<Expense>>())
        assertEquals(listOf("Albert"), alice.get("$base/merchants").body<List<Merchant>>().map { it.name })
    }

    @Test
    fun undoKeepsAnAccountAHandMadeAssetTracks() = apiTest {
        val alice = newClient().also { it.signup() }
        val base = "/api/households/${alice.createHousehold().id}"
        alice.post("$base/import") {
            setBody(HouseholdImport(currency = "CZK", accounts = listOf(ImportAccount("Card", AccountKind.CREDIT_CARD))))
        }.body<ImportResult>()
        val card = alice.get("$base/accounts").body<List<Account>>().single()
        val debt = alice.post("$base/assets") {
            setBody(dev.spendingtracker.shared.api.AssetInput("Card debt", AssetKind.CARD_DEBT, accountId = card.id))
        }.body<Asset>()

        val imp = alice.get("$base/imports").body<List<ImportSummary>>().single()
        val undo = alice.post("$base/imports/${imp.id}/undo").body<ImportUndoResult>()
        assertEquals(1, undo.kept.accounts)
        assertTrue(undo.keptItems.any { it.kind == "account" && it.name == "Card" && it.reason == "Tracked by an asset" })
        assertEquals(card.id, alice.get("$base/assets").body<List<Asset>>().single { it.id == debt.id }.accountId)
    }

    @Test
    fun undoKeepsUsedContainersCountsEditedAndUnlinksExternalRows() = apiTest {
        val alice = newClient().also { it.signup() }
        val bob = newClient().also { it.signup("bob@example.com", "Bob") }
        val home = alice.createHousehold()
        val base = "/api/households/${home.id}"

        alice.post("$base/import") { setBody(file) }.body<ImportResult>()
        val imp = alice.get("$base/imports").body<List<ImportSummary>>().single()

        // 1. Hand-entered expense on imported "Joint" account (keeps Joint account).
        val accounts = alice.get("$base/accounts").body<List<Account>>().associateBy { it.name }
        val joint = accounts.getValue("Joint")
        val groceries = alice.get("$base/categories").body<List<Category>>().single { it.name == "Groceries" }
        alice.post("$base/expenses") {
            setBody(ExpenseInput(LocalDate(2026, 9, 1), 150_00, "Bakery", categoryId = groceries.id, accountId = joint.id))
        }

        // 2. Claimed member: Bob accepts an invite claiming the imported Bob member (never deleted).
        val importedBob = alice.get("$base/members").body<List<Member>>().single { it.displayName == "Bob" }
        val invite = alice.post("$base/invites") {
            setBody(CreateInviteRequest("bob@example.com", claimUserId = importedBob.userId))
        }.body<Invite>()
        bob.post("/api/invites/${invite.token}/accept")

        // Non-owner cannot list imports or undo an import.
        bob.get("$base/imports").assertError(HttpStatusCode.Forbidden, "owner_only")
        bob.post("$base/imports/${imp.id}/undo").assertError(HttpStatusCode.Forbidden, "owner_only")

        // 3. Edit an imported expense (version becomes 2 -> counted in edited).
        val e1 = alice.get("$base/expenses").body<List<Expense>>().single { it.amountMinor == 1_200_00L }
        alice.put("$base/expenses/${e1.id}") {
            setBody(
                ExpenseInput(
                    e1.date,
                    1_250_00,
                    e1.merchant,
                    categoryId = e1.categoryId,
                    accountId = e1.accountId,
                    paidByUserId = e1.paidByUserId,
                    tripId = e1.tripId,
                    version = e1.version,
                ),
            )
        }

        // 4. Statement upload on a separate feed linked to an imported expense goes back to pending.
        val externalFeed = alice.post("$base/bank-feeds") {
            setBody(BankFeedInput(joint.id, "Manual statement", BankInstitution.OTHER, currency = "CZK"))
        }.body<BankFeed>()
        val stmtJson = """
            {"transactions":{"booked":[{
              "transactionId":"stmt-1",
              "bookingDate":"2026-08-03",
              "transactionAmount":{"amount":"-300.00","currency":"CZK"},
              "creditorName":"OMV"
            }]}}
        """.trimIndent()
        alice.post("$base/bank-feeds/${externalFeed.id}/statements") { setBody(StatementUpload(stmtJson, "stmt.json")) }
        val stmtRow = alice.get("$base/bank-rows?feedId=${externalFeed.id}").body<List<BankRow>>().single()
        val e2 = alice.get("$base/expenses").body<List<Expense>>().single { it.amountMinor == 300_00L }
        alice.post("$base/bank-rows/link") { setBody(LinkBankRows(listOf(BankRowRef(stmtRow.id, stmtRow.version)), expenseId = e2.id)) }

        // 5. A hand-entered refund pointing at e2 loses refundOf.
        val refund = alice.post("$base/expenses") {
            setBody(ExpenseInput(LocalDate(2026, 8, 4), -100_00, "OMV", categoryId = groceries.id, refundOfId = e2.id))
        }.body<Expense>()

        // Dry run reports edited=1 expense, kept Joint account + Bob member, unlinkedBankRows=1, unlinkedRefunds=1.
        val dry = alice.post("$base/imports/${imp.id}/undo?dryRun=true").body<ImportUndoResult>()
        assertEquals(1, dry.edited.expenses)
        assertEquals(1, dry.kept.accounts)
        assertEquals(1, dry.kept.members)
        assertEquals(1, dry.unlinkedBankRows)
        assertEquals(1, dry.unlinkedRefunds)
        assertTrue(dry.keptItems.any { it.kind == "account" && it.name == "Joint" })
        assertTrue(dry.keptItems.any { it.kind == "member" && it.name == "Bob" && it.reason == "Has a login" })

        val undo = alice.post("$base/imports/${imp.id}/undo").body<ImportUndoResult>()
        assertEquals(1, undo.edited.expenses)
        assertEquals(1, undo.kept.accounts)
        assertEquals(1, undo.kept.members)
        assertEquals(1, undo.unlinkedBankRows)
        assertEquals(1, undo.unlinkedRefunds)

        // Joint account and Bob member survived; Bob's card did not.
        val remainingAccounts = alice.get("$base/accounts").body<List<Account>>().map { it.name }
        assertEquals(listOf("Joint"), remainingAccounts)
        val remainingMembers = alice.get("$base/members").body<List<Member>>().map { it.displayName }
        assertEquals(listOf("Alice", "Bob"), remainingMembers.sorted())

        // Statement row went back to pending, and refund lost refundOfId.
        val stmtAfter = alice.get("$base/bank-rows?feedId=${externalFeed.id}").body<List<BankRow>>().single()
        assertEquals(BankRowOutcome.PENDING, stmtAfter.outcome)
        assertNull(stmtAfter.expenseId)
        val refundAfter = alice.get("$base/expenses").body<List<Expense>>().single { it.id == refund.id }
        assertNull(refundAfter.refundOfId)
    }
}
