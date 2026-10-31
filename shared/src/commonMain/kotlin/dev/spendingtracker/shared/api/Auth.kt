package dev.spendingtracker.shared.api

import kotlinx.serialization.Serializable

@Serializable
data class SignupRequest(val email: String, val password: String, val displayName: String)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class Me(val id: String, val email: String, val displayName: String)
