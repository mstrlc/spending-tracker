rootProject.name = "spending-tracker"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// The API image builds from a docker context that allowlists only gradle files, shared/ and server/ (see
// .dockerignore), so include :android only when its directory is actually there.
include(":shared", ":server")
if (file("android").isDirectory) include(":android")
