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

/**
 * A household member. One without a login ([hasLogin] false) can't sign in: things can belong to them, and an invite
 * can hand their place over to a real account later.
 */
@Serializable
data class Member(val userId: String, val displayName: String, val role: HouseholdRole, val hasLogin: Boolean, val legalName: String = "")

/** Body for adding a member without a login, or updating a member's names. */
@Serializable
data class MemberInput(val displayName: String, val legalName: String? = null)
