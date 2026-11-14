import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjdk-release=21")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }
}

application {
    mainClass.set("dev.spendingtracker.server.ApplicationKt")
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.java)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.openapi)
    implementation(libs.ktor.swagger.ui)
    implementation(libs.schema.kenerator.core)
    implementation(libs.schema.kenerator.swagger)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.kotlin.datetime)
    implementation(libs.argon2)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(libs.logback)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.testcontainers.postgresql)
}

// The root .env (gitignored, local secrets such as the GoCardless keys) is loaded into the server's environment;
// variables already set win.
tasks.named<JavaExec>("run") {
    val envFile = rootProject.file(".env")
    if (envFile.isFile) {
        envFile.readLines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
            .forEach { line ->
                val key = line.substringBefore("=").removePrefix("export ").trim()
                val value = line.substringAfter("=").trim().removeSurrounding("\"").removeSurrounding("'")
                if (key.isNotEmpty() && System.getenv(key) == null) environment(key, value)
            }
    }
}

tasks.test {
    useJUnitPlatform()
}
