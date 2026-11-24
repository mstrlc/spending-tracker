package dev.spendingtracker.server.auth

import de.mkammerer.argon2.Argon2Factory
import dev.spendingtracker.server.common.badRequest

/** Argon2id password hashing (OWASP baseline: 64 MiB memory, 3 iterations). */
object Passwords {
    private val argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)
    private const val ITERATIONS = 3
    private const val MEMORY_KIB = 65_536
    private const val PARALLELISM = 1

    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 128

    // Verified against when the email is unknown, so login takes the same time either way.
    private val dummyHash by lazy { hash("not-a-real-password") }

    fun hash(password: String): String = argon2.hash(ITERATIONS, MEMORY_KIB, PARALLELISM, password.toCharArray())

    fun verify(hash: String?, password: String): Boolean =
        argon2.verify(hash ?: dummyHash, password.toCharArray()) && hash != null

    /** Throws a 400 naming [field] unless [password] is an acceptable new password. */
    fun requireValid(password: String, field: String = "password") {
        if (password.length !in MIN_LENGTH..MAX_LENGTH) {
            throw badRequest("invalid_password", "Password must be $MIN_LENGTH to $MAX_LENGTH characters", field)
        }
    }
}
