import java.util.Properties
import org.gradle.api.GradleException

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

data class ReleaseInput(val propertyName: String, val environmentName: String)

fun loadReleaseProperties(): Properties {
    val explicitPath = providers.gradleProperty("planterior.release.configFile").orNull
    val source =
        if (explicitPath != null) {
            file(explicitPath).takeIf { it.isFile }
                ?: throw GradleException("Release configuration file does not exist.")
        } else {
            rootProject.file("local.properties").takeIf { it.isFile }
        }
    return Properties().apply { source?.inputStream()?.use(::load) }
}

val releaseProperties = loadReleaseProperties()

fun releaseValue(input: ReleaseInput): String? =
    providers.gradleProperty(input.propertyName).orNull?.trim()?.takeIf(String::isNotEmpty)
        ?: providers
            .environmentVariable(input.environmentName)
            .orNull
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        ?: releaseProperties.getProperty(input.propertyName)?.trim()?.takeIf(String::isNotEmpty)

fun javaStringLiteral(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else ->
                if (character.code in 0..31) append("\\u%04x".format(character.code))
                else append(character)
        }
    }
    append('"')
}

val authInputs =
    linkedMapOf(
        "GOOGLE_WEB_CLIENT_ID" to
            ReleaseInput(
                "planterior.release.googleWebClientId",
                "PLANTERIOR_GOOGLE_WEB_CLIENT_ID",
            ),
        "FIREBASE_PROJECT_ID" to
            ReleaseInput(
                "planterior.release.firebaseProjectId",
                "PLANTERIOR_FIREBASE_PROJECT_ID",
            ),
        "FIREBASE_APP_ID" to
            ReleaseInput("planterior.release.firebaseAppId", "PLANTERIOR_FIREBASE_APP_ID"),
        "FIREBASE_API_KEY" to
            ReleaseInput("planterior.release.firebaseApiKey", "PLANTERIOR_FIREBASE_API_KEY"),
    )
val releaseAuth = authInputs.mapValues { releaseValue(it.value) }
val signingInputs =
    linkedMapOf(
        "RELEASE_STORE_FILE" to
            ReleaseInput("planterior.release.storeFile", "PLANTERIOR_RELEASE_STORE_FILE"),
        "RELEASE_STORE_PASSWORD" to
            ReleaseInput("planterior.release.storePassword", "PLANTERIOR_RELEASE_STORE_PASSWORD"),
        "RELEASE_KEY_ALIAS" to
            ReleaseInput("planterior.release.keyAlias", "PLANTERIOR_RELEASE_KEY_ALIAS"),
        "RELEASE_KEY_PASSWORD" to
            ReleaseInput("planterior.release.keyPassword", "PLANTERIOR_RELEASE_KEY_PASSWORD"),
    )
val releaseSigning = signingInputs.mapValues { releaseValue(it.value) }
val hasReleaseSigning = releaseSigning.values.all { it != null }
val missingReleaseValue = "release-configuration-required"
val minimumSdk = 29

android {
    namespace = "com.planterior.helper"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.planterior.helper"
        minSdk = minimumSdk
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "DEFAULT_LOCALE", "\"ko\"")
        buildConfigField("int", "MIN_SUPPORTED_SDK", minimumSdk.toString())
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = rootProject.file(requireNotNull(releaseSigning["RELEASE_STORE_FILE"]))
                storePassword = releaseSigning["RELEASE_STORE_PASSWORD"]
                keyAlias = releaseSigning["RELEASE_KEY_ALIAS"]
                keyPassword = releaseSigning["RELEASE_KEY_PASSWORD"]
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"\"")
            buildConfigField("String", "FIREBASE_PROJECT_ID", "\"demo-planterior\"")
            buildConfigField("String", "FIREBASE_APP_ID", "\"1:1234567890:android:debug\"")
            buildConfigField("String", "FIREBASE_API_KEY", "\"demo-api-key\"")
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField(
                "String",
                "GOOGLE_WEB_CLIENT_ID",
                javaStringLiteral(releaseAuth["GOOGLE_WEB_CLIENT_ID"] ?: missingReleaseValue),
            )
            buildConfigField(
                "String",
                "FIREBASE_PROJECT_ID",
                javaStringLiteral(releaseAuth["FIREBASE_PROJECT_ID"] ?: missingReleaseValue),
            )
            buildConfigField(
                "String",
                "FIREBASE_APP_ID",
                javaStringLiteral(releaseAuth["FIREBASE_APP_ID"] ?: missingReleaseValue),
            )
            buildConfigField(
                "String",
                "FIREBASE_API_KEY",
                javaStringLiteral(releaseAuth["FIREBASE_API_KEY"] ?: missingReleaseValue),
            )
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    androidResources {
        generateLocaleConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkDependencies = true
        warningsAsErrors = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

val validateReleaseAuthConfiguration =
    tasks.register("validateReleaseAuthConfiguration") {
        group = "verification"
        description =
            "Validates external release authentication identifiers without printing values."
        doLast {
            val missing = releaseAuth.filterValues { it == null }.keys
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "Release authentication configuration is incomplete. Set: ${missing.sorted().joinToString()}"
                )
            }
            val malformed = buildList {
                if (
                    !requireNotNull(releaseAuth["GOOGLE_WEB_CLIENT_ID"])
                        .matches(
                            Regex("^[0-9]{6,}-[A-Za-z0-9_-]{10,}\\.apps\\.googleusercontent\\.com$")
                        )
                )
                    add("GOOGLE_WEB_CLIENT_ID")
                if (
                    !requireNotNull(releaseAuth["FIREBASE_PROJECT_ID"])
                        .matches(Regex("^[a-z][a-z0-9-]{4,28}[a-z0-9]$"))
                )
                    add("FIREBASE_PROJECT_ID")
                if (
                    !requireNotNull(releaseAuth["FIREBASE_APP_ID"])
                        .matches(Regex("^[0-9]+:[0-9]+:android:[0-9a-fA-F]{16,64}$"))
                )
                    add("FIREBASE_APP_ID")
                if (
                    !requireNotNull(releaseAuth["FIREBASE_API_KEY"])
                        .matches(Regex("^AIza[0-9A-Za-z_-]{35}$"))
                )
                    add("FIREBASE_API_KEY")
            }
            if (malformed.isNotEmpty()) {
                throw GradleException(
                    "Release authentication configuration is malformed: ${malformed.sorted().joinToString()}"
                )
            }
        }
    }

val validateReleaseSigningConfiguration =
    tasks.register("validateReleaseSigningConfiguration") {
        group = "verification"
        description = "Validates external release signing inputs without printing values."
        doLast {
            val missing = releaseSigning.filterValues { it == null }.keys
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "Release signing configuration is incomplete. Set: ${missing.sorted().joinToString()}"
                )
            }
            val storePath = rootProject.file(requireNotNull(releaseSigning["RELEASE_STORE_FILE"]))
            if (!storePath.isFile) {
                throw GradleException("Release signing store file does not exist.")
            }
        }
    }

val validateReleaseConfiguration =
    tasks.register("validateReleaseConfiguration") {
        group = "verification"
        description = "Validates all external release inputs without printing their values."
        dependsOn(validateReleaseAuthConfiguration, validateReleaseSigningConfiguration)
    }

tasks
    .matching { it.name == "preReleaseBuild" }
    .configureEach {
        dependsOn(validateReleaseConfiguration)
    }

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))
    implementation(project(":core:database"))
    implementation(project(":feature:auth"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.room.runtime)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.functions)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.navigation.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
