package dev.spendingtracker.server

data class Config(
    val port: Int,
    val db: DbConfig,
    /** Marks the session cookie Secure. Off for local http; on anywhere served over https. */
    val secureCookies: Boolean,
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
        )
    }
}

data class DbConfig(val url: String, val user: String, val password: String)
