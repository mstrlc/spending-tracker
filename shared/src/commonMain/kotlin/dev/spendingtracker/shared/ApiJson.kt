package dev.spendingtracker.shared

import kotlinx.serialization.json.Json

/**
 * The one JSON config for the API, shared by the server and mobile clients so they can't drift:
 * unknown keys are ignored (older clients keep working when the API adds a field).
 */
val ApiJson = Json { ignoreUnknownKeys = true }
