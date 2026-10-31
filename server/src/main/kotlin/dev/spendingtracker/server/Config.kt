package dev.spendingtracker.server

data class Config(
    val port: Int,
    val db: DbConfig,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()) = Config(
            port = env["PORT"]?.toInt() ?: 8080,
            db = DbConfig(
                url = env["DB_URL"] ?: "jdbc:postgresql://localhost:5433/spending_tracker",
                user = env["DB_USER"] ?: "spending_tracker",
                password = env["DB_PASSWORD"] ?: "spending_tracker",
            ),
        )
    }
}

data class DbConfig(val url: String, val user: String, val password: String)
