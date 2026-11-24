# Release builds don't minify yet; keep Tink's and Ktor's metadata when that changes.
-keep class com.google.crypto.tink.** { *; }
-keep class io.ktor.** { *; }
-keep class kotlinx.serialization.** { *; }
