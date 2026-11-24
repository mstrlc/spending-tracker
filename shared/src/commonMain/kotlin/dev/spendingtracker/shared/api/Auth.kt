package dev.spendingtracker.shared.api

import kotlinx.serialization.Serializable

@Serializable
data class SignupRequest(val email: String, val password: String, val displayName: String)

@Serializable
data class LoginRequest(val email: String, val password: String)

/** Response of `POST /auth/token`: a bearer token for mobile clients, which set no cookie. */
@Serializable
data class TokenResponse(val token: String, val me: Me)

@Serializable
data class Me(val id: String, val email: String, val displayName: String)

/** Body for changing your own password: the current one proves it's you. */
@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

/** Body for a household owner setting a member's password. */
@Serializable
data class SetPasswordRequest(val newPassword: String)
