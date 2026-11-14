package dev.spendingtracker.server.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.timestamp

// Exposed mappings of the Flyway schema (db/migration). Flyway owns the DDL; these only describe it.

object Users : Table("users") {
    val id = uuid("id")
    /** Both null for a member without a login (V10). */
    val email = text("email").nullable()
    val passwordHash = text("password_hash").nullable()
    val displayName = text("display_name")
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}

object Sessions : Table("sessions") {
    val tokenHash = binary("token_hash")
    val userId = uuid("user_id")
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val expiresAt = timestamp("expires_at")
    override val primaryKey = PrimaryKey(tokenHash)
}

object Households : Table("households") {
    val id = uuid("id")
    val name = text("name")
    val currency = text("currency")
    /** V21: whether the inbox matcher files transfer pairs and duplicates without asking. */
    val autoMatch = bool("auto_match").default(false)
    /** V30: expenses and settlements before this date don't count toward balances. */
    val debtsFrom = date("debts_from").nullable()
    /** V31: whether joint accounts act as a pot or count top-ups toward debts. */
    val jointFunding = text("joint_funding").default("pot")
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}

object HouseholdMembers : Table("household_members") {
    val householdId = uuid("household_id")
    val userId = uuid("user_id")
    val role = text("role")
    val legalName = text("legal_name").default("")
    val importId = uuid("import_id").nullable()
    override val primaryKey = PrimaryKey(householdId, userId)
}

object Accounts : Table("accounts") {
    val id = uuid("id")
    val householdId = uuid("household_id")
    val name = text("name")
    val ownerUserId = uuid("owner_user_id").nullable()
    val status = text("status")
    val kind = text("kind")
    val iban = text("iban").nullable()
    /** V40: the last four digits of the account's cards, space-separated. */
    val cards = text("cards").default("")
    val interestTaxPercent = integer("interest_tax_percent").nullable()
    val importId = uuid("import_id").nullable()
    val version = integer("version")
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}

object Categories : Table("categories") {
    val id = uuid("id")
    val householdId = uuid("household_id")
    val parentId = uuid("parent_id").nullable()
    val name = text("name")
    val kind = text("kind")
    val archived = bool("archived")
    val sortOrder = integer("sort_order")
    val importId = uuid("import_id").nullable()
    val version = integer("version")
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}

object Expenses : Table("expenses") {
    val id = uuid("id")
    val householdId = uuid("household_id")
    val date = date("date")
    val amountMinor = long("amount_minor")
    val merchantId = uuid("merchant_id").nullable()
    val categoryId = uuid("category_id").nullable()
    val accountId = uuid("account_id").nullable()
    val paidByUserId = uuid("paid_by_user_id").nullable()
    val notes = text("notes")
    val tripId = uuid("trip_id").nullable()
    val originalAmountMinor = long("original_amount_minor").nullable()
    val originalCurrency = text("original_currency").nullable()
    val externalRef = text("external_ref").nullable()
    val createsDebt = bool("creates_debt").default(true)
    /** V22: the expense this one refunds. */
    val refundOf = uuid("refund_of").nullable()
    val importId = uuid("import_id").nullable()
    val version = integer("version")
    val createdBy = uuid("created_by").nullable()
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}

object ExpenseLines : Table("expense_lines") {
    val id = uuid("id").clientDefault { kotlin.uuid.Uuid.random() }
    val householdId = uuid("household_id")
    val expenseId = uuid("expense_id")
    val sortOrder = integer("sort_order")
    val categoryId = uuid("category_id").nullable()
    val amountMinor = long("amount_minor")
    val notes = text("notes")
    val paidByUserId = uuid("paid_by_user_id").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** V16: the household default (no category, no expense), a category's override, or one expense's. */
object SplitRules : Table("split_rules") {
    val id = uuid("id")
    val householdId = uuid("household_id")
    val categoryId = uuid("category_id").nullable()
    val expenseId = uuid("expense_id").nullable()
    val validFrom = date("valid_from").nullable()
    val version = integer("version").default(1)
    val createdBy = uuid("created_by").nullable()
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}

object SplitShares : Table("split_shares") {
    val id = uuid("id").clientDefault { kotlin.uuid.Uuid.random() }
    val householdId = uuid("household_id")
    val ruleId = uuid("rule_id")
    val userId = uuid("user_id")
    val weight = integer("weight")
    override val primaryKey = PrimaryKey(id)
}

object Settlements : Table("settlements") {
    val id = uuid("id")
    val householdId = uuid("household_id")
    val date = date("date")
    val fromUserId = uuid("from_user_id")
    val toUserId = uuid("to_user_id")
    val amountMinor = long("amount_minor")
    val notes = text("notes")
    val version = integer("version").default(1)
    val createdBy = uuid("created_by").nullable()
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}

object Merchants : Table("merchants") {
    val id = uuid("id")
    val householdId = uuid("household_id")
    val name = text("name")
    val defaultCategoryId = uuid("default_category_id").nullable()
    val importId = uuid("import_id").nullable()
    val version = integer("version").default(1)
    val createdBy = uuid("created_by").nullable()
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}

object Trips : Table("trips") {
    val id = uuid("id")
    val householdId = uuid("household_id")
    val name = text("name")
    val startDate = date("start_date").nullable()
    val endDate = date("end_date").nullable()
    val notes = text("notes")
    val importId = uuid("import_id").nullable()
    val version = integer("version").default(1)
    val createdBy = uuid("created_by").nullable()
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}

object Income : Table("income") {
    val id = uuid("id")
    val householdId = uuid("household_id")
    val date = date("date")
    /** First day of the month the income was earned in (V14). */
    val earnedMonth = date("earned_month")
    /** earning or deduction (V17). */
    val kind = text("kind")
    /** Earning this deduction was taken off (V25). */
    val deductedFrom = uuid("deducted_from").nullable()
    val amountMinor = long("amount_minor")
    // Not `source`: that name is taken by Table.source.
    val sourceName = text("source")
    val categoryId = uuid("category_id").nullable()
    val accountId = uuid("account_id").nullable()
    val recipientUserId = uuid("recipient_user_id").nullable()
    val notes = text("notes")
    val externalRef = text("external_ref").nullable()
    val importId = uuid("import_id").nullable()
    val version = integer("version")
    val createdBy = uuid("created_by").nullable()
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}

object Assets : Table("assets") {
    val id = uuid("id")
    val householdId = uuid("household_id")
    val name = text("name")
    val kind = text("kind")
    val ownerUserId = uuid("owner_user_id").nullable()
    val notes = text("notes")
    val symbol = text("symbol").nullable()
    val importId = uuid("import_id").nullable()
    val accountId = uuid("account_id").nullable()
    val version = integer("version")
    val createdBy = uuid("created_by").nullable()
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}

object AssetContributions : Table("asset_contributions") {
    val id = uuid("id")
    val householdId = uuid("household_id")
    val assetId = uuid("asset_id")
    val date = date("date")
    val amountMinor = long("amount_minor")
    val originalAmountMinor = long("original_amount_minor").nullable()
    val originalCurrency = text("original_currency").nullable()
    val quantity = decimal("quantity", 30, 10).nullable()
    val accountId = uuid("account_id").nullable()
    val transferId = uuid("transfer_id").nullable()
    val notes = text("notes")
    val importId = uuid("import_id").nullable()
    val version = integer("version")
    val createdBy = uuid("created_by").nullable()
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}

/** V46: an account's balance at the end of a day, entered by hand for an account the bank doesn't report. */
object AccountBalances : Table("account_balances") {
    val id = uuid("id")
    val householdId = uuid("household_id")
    val accountId = uuid("account_id")
    val date = date("date")
    val amountMinor = long("amount_minor")
    val notes = text("notes")
    val version = integer("version").default(1)
    val createdBy = uuid("created_by").nullable()
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}

object AssetValuations : Table("asset_valuations") {
    val id = uuid("id")
    val householdId = uuid("household_id")
    val assetId = uuid("asset_id")
    val date = date("date")
    val valueMinor = long("value_minor")
    val notes = text("notes")
    val version = integer("version")
    val createdBy = uuid("created_by").nullable()
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}

object AssetFees : Table("asset_fees") {
    val id = uuid("id")
    val householdId = uuid("household_id")
    val assetId = uuid("asset_id")
    val date = date("date")
    val amountMinor = long("amount_minor")
    val notes = text("notes")
    val version = integer("version")
    val createdBy = uuid("created_by").nullable()
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}

// Market data (V7): shared by every household.

object Securities : Table("securities") {
    val symbol = text("symbol")
    val name = text("name")
    val exchange = text("exchange")
    val currency = text("currency")
    override val primaryKey = PrimaryKey(symbol)
}

object SecurityPrices : Table("security_prices") {
    val symbol = text("symbol")
    val date = date("date")
    val close = decimal("close", 30, 10)
    override val primaryKey = PrimaryKey(symbol, date)
}

object FxRates : Table("fx_rates") {
    val currency = text("currency")
    val date = date("date")
    val czk = decimal("czk", 30, 10)
    override val primaryKey = PrimaryKey(currency, date)
}

object MarketFetches : Table("market_fetches") {
    val key = text("key")
    val fetchedAt = timestamp("fetched_at")
    override val primaryKey = PrimaryKey(key)
}

object HouseholdInvites : Table("household_invites") {
    val id = uuid("id")
    val householdId = uuid("household_id")
    val tokenHash = binary("token_hash")
    val email = text("email")
    val role = text("role")
    val invitedBy = uuid("invited_by").nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val expiresAt = timestamp("expires_at")
    val acceptedAt = timestamp("accepted_at").nullable()
    val acceptedBy = uuid("accepted_by").nullable()
    val claimUserId = uuid("claim_user_id").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** Written only by the audit triggers (V5); [before] and [after] are jsonb, read here as their JSON text. */
object AuditLog : Table("audit_log") {
    val id = long("id")
    val householdId = uuid("household_id")
    val at = timestamp("at")
    val actorUserId = uuid("actor_user_id").nullable()
    val entity = text("entity")
    val entityId = uuid("entity_id")
    val action = text("action")
    val before = text("before").nullable()
    val after = text("after").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** V19: what a bank provides, read into one ledger account. */
object BankFeeds : Table("bank_feeds") {
    val id = uuid("id")
    val householdId = uuid("household_id")
    val accountId = uuid("account_id")
    val name = text("name")
    val institution = text("institution")
    val feedSource = text("source")
    val externalId = text("external_id").nullable()
    val iban = text("iban").nullable()
    val currency = text("currency")
    val importFrom = date("import_from").nullable()
    val connectionId = uuid("connection_id").nullable()
    val lastSyncedAt = timestamp("last_synced_at").nullable()
    val importId = uuid("import_id").nullable()
    val version = integer("version").default(1)
    val createdBy = uuid("created_by").nullable()
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}

/** V23: one consent given at a bank; [accounts] is the bank's account list as JSON. */
object BankConnections : Table("bank_connections") {
    val id = uuid("id")
    val householdId = uuid("household_id")
    val institutionId = text("institution_id")
    val institutionName = text("institution_name")
    val requisitionId = text("requisition_id")
    val agreementId = text("agreement_id").nullable()
    val status = text("status")
    val consentExpiresAt = timestamp("consent_expires_at").nullable()
    val accounts = jsonb("accounts")
    val lastError = text("last_error")
    val version = integer("version").default(1)
    val createdBy = uuid("created_by").nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}

/** V23: what the bank said a feed's balance was when a sync read it. */
object BankBalances : Table("bank_balances") {
    val id = uuid("id")
    val householdId = uuid("household_id")
    val feedId = uuid("feed_id")
    val readAt = timestamp("read_at").defaultExpression(CurrentTimestamp)
    val balanceDate = date("balance_date").nullable()
    val amountMinor = long("amount_minor")
    val currency = text("currency")
    val balanceType = text("balance_type")
    override val primaryKey = PrimaryKey(id)
}

/** V23: each time a sync asked the bank for a feed's data, to hold to GoCardless's daily limit. */
object BankSyncs : Table("bank_syncs") {
    val id = uuid("id")
    val householdId = uuid("household_id")
    val feedId = uuid("feed_id")
    val startedAt = timestamp("started_at").defaultExpression(CurrentTimestamp)
    val trigger = text("trigger")
    val added = integer("added")
    val error = text("error")
    override val primaryKey = PrimaryKey(id)
}

/** V19, V20, V36: one bank transaction; pending while none of [expenseId], [incomeId], [transferId], [settlementId], [contributionId] and [skipReason] is set. */
object BankRows : Table("bank_rows") {
    val id = uuid("id")
    val householdId = uuid("household_id")
    val feedId = uuid("feed_id")
    val externalId = text("external_id")
    val bookingDate = date("booking_date")
    val valueDate = date("value_date").nullable()
    val date = date("date")
    val amountMinor = long("amount_minor")
    val currency = text("currency")
    val counterpartyName = text("counterparty_name")
    val counterpartyIban = text("counterparty_iban").nullable()
    val counterpartyAccount = text("counterparty_account")
    val text = text("text")
    val message = text("message")
    val merchantKey = text("merchant_key")
    val code = text("code")
    val hint = text("hint")
    val raw = jsonb("raw")
    val expenseId = uuid("expense_id").nullable()
    val incomeId = uuid("income_id").nullable()
    val transferId = uuid("transfer_id").nullable()
    val settlementId = uuid("settlement_id").nullable()
    /** V36: the asset contribution the row became. */
    val contributionId = uuid("contribution_id").nullable()
    /** V37: whether suggestions for later rows with the same descriptor may come from this one. */
    val remember = bool("remember").default(true)
    /** V38: the expense a pending row will become, prepared ahead (SplitDraft as JSON). */
    val splitDraft = jsonb("split_draft").nullable()
    val skipReason = text("skip_reason").nullable()
    val createdRecord = bool("created_record").default(false)
    val decidedBy = uuid("decided_by").nullable()
    val decidedAuto = bool("decided_auto").default(false)
    /** V21: the rule that filed the row automatically; null for a person's or the matcher's decision. */
    val ruleId = uuid("rule_id").nullable()
    val decidedAt = timestamp("decided_at").nullable()
    val importId = uuid("import_id").nullable()
    val version = integer("version").default(1)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}

/** V20: money moving between the household's own accounts. */
object Transfers : Table("transfers") {
    val id = uuid("id")
    val householdId = uuid("household_id")
    val date = date("date")
    val amountMinor = long("amount_minor")
    val fromAccountId = uuid("from_account_id")
    val toAccountId = uuid("to_account_id")
    val notes = text("notes")
    val version = integer("version").default(1)
    val createdBy = uuid("created_by").nullable()
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}

/** V21: rules mapping bank rows to an outcome. */
object InboxRules : Table("inbox_rules") {
    val id = uuid("id")
    val householdId = uuid("household_id")
    val name = text("name")
    val counterpartyIban = text("counterparty_iban").nullable()
    val merchantKey = text("merchant_key").nullable()
    val textContains = text("text_contains").nullable()
    val feedId = uuid("feed_id").nullable()
    val accountId = uuid("account_id").nullable()
    val direction = text("direction").nullable()
    val outcome = text("outcome")
    val merchant = text("merchant")
    val categoryId = uuid("category_id").nullable()
    val personUserId = uuid("person_user_id").nullable()
    val incomeSource = text("income_source")
    val otherAccountId = uuid("other_account_id").nullable()
    val settleFromUserId = uuid("settle_from_user_id").nullable()
    val settleToUserId = uuid("settle_to_user_id").nullable()
    /** V36: for a contribution rule, the asset the money goes into. */
    val assetId = uuid("asset_id").nullable()
    val skipReason = text("skip_reason")
    val monthOffset = integer("month_offset").default(-1)
    val auto = bool("auto").default(false)
    val priority = integer("priority").default(0)
    val version = integer("version").default(1)
    val createdBy = uuid("created_by").nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}

/** V26: an import batch, so it can be listed and undone as a whole. */
object Imports : Table("imports") {
    val id = uuid("id")
    val householdId = uuid("household_id")
    val createdBy = uuid("created_by").nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val counts = jsonb("counts")
    val undoneAt = timestamp("undone_at").nullable()
    val undoneBy = uuid("undone_by").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** V44: an e-shop order read from the shop's email; [bankRowId] is the row that paid for it. */
object Orders : Table("orders") {
    val id = uuid("id")
    val householdId = uuid("household_id")
    val shop = text("shop")
    val number = text("number")
    val orderedOn = date("ordered_on")
    val totalMinor = long("total_minor")
    val currency = text("currency")
    val merchant = text("merchant")
    val bankRowId = uuid("bank_row_id").nullable()
    val matchedByHand = bool("matched_by_hand").default(false)
    val raw = text("raw").nullable()
    val version = integer("version").default(1)
    val createdBy = uuid("created_by").nullable()
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}

/** V44: one line of an order. */
object OrderItems : Table("order_items") {
    val id = uuid("id")
    val householdId = uuid("household_id")
    val orderId = uuid("order_id")
    val position = integer("position")
    val kind = text("kind")
    val name = text("name")
    val quantity = decimal("quantity", 12, 3)
    val amountMinor = long("amount_minor")
    val categoryId = uuid("category_id").nullable()
    val paidByUserId = uuid("paid_by_user_id").nullable()
    val assigned = bool("assigned").default(false)
    override val primaryKey = PrimaryKey(id)
}

/** V45: a merchant charged on a schedule (see recurring/Recurring.kt). */
object RecurringPayments : Table("recurring_payments") {
    val id = uuid("id")
    val householdId = uuid("household_id")
    val merchantId = uuid("merchant_id")
    val status = text("status")
    val expectedAmountMinor = long("expected_amount_minor")
    val intervalMonths = integer("interval_months")
    val anchorDate = date("anchor_date")
    val categoryId = uuid("category_id").nullable()
    val accountId = uuid("account_id").nullable()
    val paidByUserId = uuid("paid_by_user_id").nullable()
    val notes = text("notes")
    val version = integer("version")
    val createdBy = uuid("created_by").nullable()
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}
