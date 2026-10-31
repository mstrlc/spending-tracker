package dev.spendingtracker.shared.api

import kotlinx.serialization.Serializable

@Serializable
data class CreateInviteRequest(val email: String, val role: HouseholdRole = HouseholdRole.MEMBER)

/** An open invite as owners see it. [token] is present only in the response that created it. */
@Serializable
data class Invite(
    val id: String,
    val email: String,
    val role: HouseholdRole,
    val invitedBy: String?,
    val createdAt: String,
    val expiresAt: String,
    val token: String? = null,
)

/** What the holder of an invite link sees before accepting. */
@Serializable
data class InvitePreview(
    val householdName: String,
    val invitedBy: String?,
    val email: String,
    val role: HouseholdRole,
    val expiresAt: String,
)
