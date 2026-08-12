plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android { testOptions { unitTests.isIncludeAndroidResources = true } }

dependencies {
    api(project(":core:model"))
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.coroutines.core)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }
