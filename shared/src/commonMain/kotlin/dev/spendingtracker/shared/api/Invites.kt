package dev.spendingtracker.shared.api

import kotlinx.serialization.Serializable

/** [claimUserId]: a member without a login whose place the invitee takes, with everything that belongs to them. */
@Serializable
data class CreateInviteRequest(val email: String, val role: HouseholdRole = HouseholdRole.MEMBER, val claimUserId: String? = null)

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
    /** The member without a login this invite hands over, and their name. */
    val claimUserId: String? = null,
    val claimName: String? = null,
)

/** What the holder of an invite link sees before accepting. */
@Serializable
data class InvitePreview(
    val householdName: String,
    val invitedBy: String?,
    val email: String,
    val role: HouseholdRole,
    val expiresAt: String,
    /** Accepting takes over this member's place (a member without a login), with everything that belongs to them. */
    val claimName: String? = null,
)
