package dev.spendingtracker.shared.api

import kotlinx.serialization.Serializable

/** Error body for every non-2xx response. [field] names the offending request field, when there is one. */
@Serializable
data class ApiError(val code: String, val message: String? = null, val field: String? = null)
