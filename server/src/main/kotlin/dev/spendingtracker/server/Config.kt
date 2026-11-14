package dev.spendingtracker.server

data class Config(
    val port: Int,
    val db: DbConfig,
    /** Marks the session cookie Secure. Off for local http; on anywhere served over https. */
    val secureCookies: Boolean,
    /** GoCardless Bank Account Data keys; null without them, and bank connections are then reported as not configured. */
    val goCardless: GoCardlessKeys?,
    /** Where the web app is served: the bank sends people back to it after they agree to share their data. */
    val appUrl: String,
    /** The hour (0-23, Prague time) the daily bank sync runs; null turns it off. */
    val bankSyncHour: Int?,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()) = Config(
            port = env["PORT"]?.toInt() ?: 8080,
            db = DbConfig(
                url = env["DB_URL"] ?: "jdbc:postgresql://localhost:5433/spending_tracker",
                user = env["DB_USER"] ?: "spending_tracker",
                password = env["DB_PASSWORD"] ?: "spending_tracker",
            ),
            secureCookies = env["SECURE_COOKIES"]?.toBoolean() ?: false,
            goCardless = env["GOCARDLESS_SECRET_ID"]?.trim()?.takeIf { it.isNotEmpty() }?.let { id ->
                env["GOCARDLESS_SECRET_KEY"]?.trim()?.takeIf { it.isNotEmpty() }?.let { GoCardlessKeys(id, it) }
            },
            appUrl = env["APP_URL"]?.trim()?.takeIf { it.isNotEmpty() } ?: "http://localhost:5173",
            bankSyncHour = syncHour(env["BANK_SYNC_HOUR"]),
        )

        /** Absent means 5 (Prague time), "off" none. */
        private fun syncHour(value: String?): Int? = when {
            value == null || value.isBlank() -> 5
            value.trim().equals("off", ignoreCase = true) -> null
            else -> value.trim().toIntOrNull()?.takeIf { it in 0..23 } ?: error("BANK_SYNC_HOUR must be 0-23 or off")
        }
    }
}

data class DbConfig(val url: String, val user: String, val password: String)

/** Never printed: this class has no toString that shows the key. */
class GoCardlessKeys(val secretId: String, val secretKey: String) {
    override fun toString() = "GoCardlessKeys(…)"
}
