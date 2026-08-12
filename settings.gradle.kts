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

rootProject.name = "PlanteriorHelper"

include(
    ":app",
    ":core:model",
    ":core:designsystem",
    ":core:data",
    ":core:database",
    ":core:network",
    ":core:testing",
    ":feature:auth",
    ":feature:home",
    ":feature:camera",
    ":feature:identify",
    ":feature:registration",
    ":feature:collection",
    ":feature:watering",
    ":feature:weather",
    ":feature:minihome",
    ":feature:shop",
    ":feature:share",
    ":feature:settings",
)
