pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "pulse"

include(
    ":app",
    ":wire",
    ":ble",
    ":link",
    ":sync",
    ":data",
    ":metrics",
    ":feature:connect",
    ":feature:live",
    ":feature:sync",
    ":feature:debug",
    ":testing:fakestrap",
    ":testing:golden",
)
