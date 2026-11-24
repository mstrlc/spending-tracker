package dev.spendingtracker.shared.client

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.java.Java

internal actual fun defaultEngine(debugBuild: Boolean): HttpClientEngine = Java.create()
