package dev.spendingtracker.shared.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class HouseholdRole {
    @SerialName("owner") OWNER,
    @SerialName("member") MEMBER,
}

@Serializable
data class CreateHouseholdRequest(val name: String, val currency: String)

@Serializable
data class Household(val id: String, val name: String, val currency: String, val role: HouseholdRole)

@Serializable
data class Member(val userId: String, val displayName: String, val role: HouseholdRole)
