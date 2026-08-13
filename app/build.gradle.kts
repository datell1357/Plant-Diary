import org.gradle.api.GradleException

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

val signingEnvironmentKeys =
    listOf(
        "PLANTERIOR_RELEASE_STORE_FILE",
        "PLANTERIOR_RELEASE_STORE_PASSWORD",
        "PLANTERIOR_RELEASE_KEY_ALIAS",
        "PLANTERIOR_RELEASE_KEY_PASSWORD",
    )
val signingEnvironment = signingEnvironmentKeys.associateWith(System::getenv)
val hasReleaseSigning = signingEnvironment.values.all { !it.isNullOrBlank() }
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
        buildConfigField(
            "String",
            "GOOGLE_WEB_CLIENT_ID",
            "\"${System.getenv("PLANTERIOR_GOOGLE_WEB_CLIENT_ID").orEmpty()}\"",
        )
        buildConfigField(
            "String",
            "FIREBASE_PROJECT_ID",
            "\"${System.getenv("PLANTERIOR_FIREBASE_PROJECT_ID").orEmpty()}\"",
        )
        buildConfigField(
            "String",
            "FIREBASE_APP_ID",
            "\"${System.getenv("PLANTERIOR_FIREBASE_APP_ID").orEmpty()}\"",
        )
        buildConfigField(
            "String",
            "FIREBASE_API_KEY",
            "\"${System.getenv("PLANTERIOR_FIREBASE_API_KEY").orEmpty()}\"",
        )
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile =
                    file(requireNotNull(signingEnvironment["PLANTERIOR_RELEASE_STORE_FILE"]))
                storePassword = signingEnvironment["PLANTERIOR_RELEASE_STORE_PASSWORD"]
                keyAlias = signingEnvironment["PLANTERIOR_RELEASE_KEY_ALIAS"]
                keyPassword = signingEnvironment["PLANTERIOR_RELEASE_KEY_PASSWORD"]
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            buildConfigField("String", "FIREBASE_PROJECT_ID", "\"demo-planterior\"")
            buildConfigField("String", "FIREBASE_APP_ID", "\"1:1234567890:android:debug\"")
            buildConfigField("String", "FIREBASE_API_KEY", "\"demo-api-key\"")
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"\"")
            buildConfigField("String", "FIREBASE_PROJECT_ID", "\"\"")
            buildConfigField("String", "FIREBASE_APP_ID", "\"\"")
            buildConfigField("String", "FIREBASE_API_KEY", "\"\"")
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

val validateReleaseConfiguration =
    tasks.register("validateReleaseConfiguration") {
        group = "verification"
        description = "Validates release signing inputs without printing their values."
        doLast {
            val missing = signingEnvironment.filterValues { it.isNullOrBlank() }.keys
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "Release signing configuration is incomplete. Set: ${missing.sorted().joinToString()}"
                )
            }
            val storePath = requireNotNull(signingEnvironment["PLANTERIOR_RELEASE_STORE_FILE"])
            if (!file(storePath).isFile) {
                throw GradleException("Release signing store file does not exist.")
            }
        }
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
