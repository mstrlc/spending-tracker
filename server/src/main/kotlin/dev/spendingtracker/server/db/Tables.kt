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
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}

object HouseholdMembers : Table("household_members") {
    val householdId = uuid("household_id")
    val userId = uuid("user_id")
    val role = text("role")
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
    val version = integer("version")
    val updatedAt = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}

object Expenses : Table("expenses") {
    val id = uuid("id")
    val householdId = uuid("household_id")
    val date = date("date")
    val amountMinor = long("amount_minor")
    val merchant = text("merchant")
    val categoryId = uuid("category_id").nullable()
    val accountId = uuid("account_id").nullable()
    val paidByUserId = uuid("paid_by_user_id").nullable()
    val notes = text("notes")
    val tripId = uuid("trip_id").nullable()
    val originalAmountMinor = long("original_amount_minor").nullable()
    val originalCurrency = text("original_currency").nullable()
    val externalRef = text("external_ref").nullable()
    val version = integer("version")
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
    val amountMinor = long("amount_minor")
    // Not `source`: that name is taken by Table.source.
    val sourceName = text("source")
    val accountId = uuid("account_id").nullable()
    val recipientUserId = uuid("recipient_user_id").nullable()
    val notes = text("notes")
    val externalRef = text("external_ref").nullable()
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
    val quantity = decimal("quantity", 30, 10).nullable()
    val accountId = uuid("account_id").nullable()
    val notes = text("notes")
    val version = integer("version")
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
