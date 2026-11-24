package dev.spendingtracker.shared.client

import dev.spendingtracker.shared.api.ApiError

/** A non-2xx API response, with its decoded [ApiError] body (code, message, field). */
class ApiException(val status: Int, val error: ApiError) :
    Exception("HTTP $status ${error.code}" + (error.message?.let { ": $it" } ?: ""))
