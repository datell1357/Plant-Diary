import java.security.KeyStore
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

object ReleaseValidationConstants {
    const val MISSING_VALUE = "release-configuration-required"
}

data class ReleaseInput(val propertyName: String, val environmentName: String)

@DisableCachingByDefault(because = "Validation must execute for every requested release build")
abstract class ValidateReleaseAuthConfigurationTask : DefaultTask() {
    @get:Input abstract val values: MapProperty<String, String>

    @TaskAction
    fun validate() {
        val configured = values.get()
        val missing =
            configured.filterValues { it == ReleaseValidationConstants.MISSING_VALUE }.keys
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release authentication configuration is incomplete. Set: ${missing.sorted().joinToString()}"
            )
        }
        val malformed = buildList {
            if (
                !configured
                    .getValue("GOOGLE_WEB_CLIENT_ID")
                    .matches(
                        Regex("^[0-9]{6,}-[A-Za-z0-9_-]{10,}\\.apps\\.googleusercontent\\.com$")
                    )
            )
                add("GOOGLE_WEB_CLIENT_ID")
            if (
                !configured
                    .getValue("FIREBASE_PROJECT_ID")
                    .matches(Regex("^[a-z][a-z0-9-]{4,28}[a-z0-9]$"))
            )
                add("FIREBASE_PROJECT_ID")
            if (
                !configured
                    .getValue("FIREBASE_APP_ID")
                    .matches(Regex("^[0-9]+:[0-9]+:android:[0-9a-fA-F]{16,64}$"))
            )
                add("FIREBASE_APP_ID")
            if (!configured.getValue("FIREBASE_API_KEY").matches(Regex("^AIza[0-9A-Za-z_-]{35}$")))
                add("FIREBASE_API_KEY")
        }
        if (malformed.isNotEmpty()) {
            throw GradleException(
                "Release authentication configuration is malformed: ${malformed.sorted().joinToString()}"
            )
        }
    }
}

@DisableCachingByDefault(
    because = "Signing credentials must be opened for every requested release build"
)
abstract class ValidateReleaseSigningConfigurationTask : DefaultTask() {
    @get:Input abstract val values: MapProperty<String, String>

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val storeFile: RegularFileProperty

    @TaskAction
    fun validate() {
        val configured = values.get()
        val missing =
            configured.filterValues { it == ReleaseValidationConstants.MISSING_VALUE }.keys
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release signing configuration is incomplete. Set: ${missing.sorted().joinToString()}"
            )
        }
        val file = storeFile.orNull?.asFile
        if (file == null || !file.isFile) {
            throw GradleException("Release signing store file does not exist.")
        }
        val storePassword = configured.getValue("RELEASE_STORE_PASSWORD").toCharArray()
        val keyPassword = configured.getValue("RELEASE_KEY_PASSWORD").toCharArray()
        try {
            val keyStore = KeyStore.getInstance(file, storePassword)
            val alias = configured.getValue("RELEASE_KEY_ALIAS")
            val key = keyStore.getKey(alias, keyPassword)
            if (!keyStore.isKeyEntry(alias) || key !is java.security.PrivateKey) {
                throw GradleException("Release signing credentials could not be verified.")
            }
        } catch (error: GradleException) {
            throw error
        } catch (_: Exception) {
            throw GradleException("Release signing credentials could not be verified.")
        } finally {
            storePassword.fill('\u0000')
            keyPassword.fill('\u0000')
        }
    }
}

fun loadReleaseProperties(): Properties {
    val explicitPath = providers.gradleProperty("planterior.release.configFile").orNull
    val source =
        if (explicitPath != null) {
            file(explicitPath).takeIf { it.isFile }
                ?: throw GradleException("Release configuration file does not exist.")
        } else {
            rootProject.file("local.properties").takeIf { it.isFile }
        }
    return Properties().apply {
        source?.let {
            providers
                .fileContents(layout.projectDirectory.file(it.absolutePath))
                .asText
                .get()
                .reader()
                .use(::load)
        }
    }
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
                javaStringLiteral(
                    releaseAuth["GOOGLE_WEB_CLIENT_ID"] ?: ReleaseValidationConstants.MISSING_VALUE
                ),
            )
            buildConfigField(
                "String",
                "FIREBASE_PROJECT_ID",
                javaStringLiteral(
                    releaseAuth["FIREBASE_PROJECT_ID"] ?: ReleaseValidationConstants.MISSING_VALUE
                ),
            )
            buildConfigField(
                "String",
                "FIREBASE_APP_ID",
                javaStringLiteral(
                    releaseAuth["FIREBASE_APP_ID"] ?: ReleaseValidationConstants.MISSING_VALUE
                ),
            )
            buildConfigField(
                "String",
                "FIREBASE_API_KEY",
                javaStringLiteral(
                    releaseAuth["FIREBASE_API_KEY"] ?: ReleaseValidationConstants.MISSING_VALUE
                ),
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
    tasks.register<ValidateReleaseAuthConfigurationTask>("validateReleaseAuthConfiguration") {
        group = "verification"
        description =
            "Validates external release authentication identifiers without printing values."
        values.set(releaseAuth.mapValues { it.value ?: ReleaseValidationConstants.MISSING_VALUE })
    }

val validateReleaseSigningConfiguration =
    tasks.register<ValidateReleaseSigningConfigurationTask>("validateReleaseSigningConfiguration") {
        group = "verification"
        description = "Validates external release signing inputs without printing values."
        values.set(
            releaseSigning.mapValues { it.value ?: ReleaseValidationConstants.MISSING_VALUE }
        )
        releaseSigning["RELEASE_STORE_FILE"]?.let { storeFile.set(rootProject.file(it)) }
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
    implementation(project(":feature:camera"))
    implementation(project(":feature:collection"))
    implementation(project(":feature:home"))
    implementation(project(":feature:identify"))
    implementation(project(":feature:registration"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.room.runtime)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.functions)
    implementation(libs.firebase.storage)
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
    testImplementation(project(":feature:collection"))
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // API 37은 InputManager.getInstance를 제거해 Espresso 3.5가 동작하지 않는다.
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.exifinterface)
    androidTestImplementation(libs.androidx.room.runtime)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
