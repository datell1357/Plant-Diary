plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.spotless)
}

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**", "**/* 2.kt", "**/* 3.kt")
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
    }
    format("projectFiles") {
        target(
            ".gitignore",
            ".github/**/*.yml",
            ".github/**/*.yaml",
            "gradle.properties",
            "gradle/**/*.toml",
            "gradle/wrapper/*.properties",
            "**/src/**/*.xml",
            "**/src/**/resources.properties",
        )
        targetExclude("**/build/**")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        exclude { element -> element.file.name.matches(Regex(".* [0-9]+\\.kt")) }
    }

    plugins.withId("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension> {
            namespace = "com.planterior.helper" + project.path.replace(':', '.')
            compileSdk = 37

            defaultConfig {
                minSdk = 29
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }

            buildTypes {
                debug {
                    isMinifyEnabled = false
                }
                release {
                    isMinifyEnabled = false
                }
            }

            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }

            lint {
                abortOnError = true
                warningsAsErrors = true
            }
        }
    }
}

val qualityModules = subprojects.filter { it.buildFile.isFile }.map { it.path }

tasks.register("lintDebug") {
    group = "verification"
    description = "Runs Android lint for every module's debug variant."
    dependsOn(qualityModules.map { "$it:lintDebug" })
}

tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Runs debug unit tests for every Android module."
    dependsOn(qualityModules.map { "$it:testDebugUnitTest" })
}

tasks.register("assembleDebug") {
    group = "build"
    description = "Assembles every module's debug variant."
    dependsOn(qualityModules.map { "$it:assembleDebug" })
}
