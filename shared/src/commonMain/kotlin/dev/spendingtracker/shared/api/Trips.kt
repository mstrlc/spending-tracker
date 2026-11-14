package dev.spendingtracker.shared.api

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/** A trip; expenses can belong to one. [spentMinor] and [expenseCount] add up its expenses. */
@Serializable
data class Trip(
    val id: String,
    val name: String,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val notes: String,
    val version: Int,
    val spentMinor: Long,
    val expenseCount: Int,
)

/** Body for creating and updating a trip; [version] is required when updating. */
@Serializable
data class TripInput(
    val name: String,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val notes: String = "",
    val version: Int? = null,
)
