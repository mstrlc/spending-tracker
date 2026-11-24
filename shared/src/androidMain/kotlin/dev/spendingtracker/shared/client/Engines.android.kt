package dev.spendingtracker.shared.client

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun defaultEngine(): HttpClientEngine = OkHttp.create()
