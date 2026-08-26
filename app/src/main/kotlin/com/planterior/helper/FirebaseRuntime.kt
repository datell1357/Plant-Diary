package com.planterior.helper

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

object FirebaseRuntime {
    data class Configuration(
        val projectId: String,
        val applicationId: String,
        val apiKey: String,
        val storageBucket: String,
    ) {
        fun isComplete(): Boolean =
            projectId.isNotBlank() &&
                applicationId.isNotBlank() &&
                apiKey.isNotBlank() &&
                storageBucket.isNotBlank()
    }

    @Synchronized
    fun initialize(
        context: Context,
        configuration: Configuration =
            Configuration(
                BuildConfig.FIREBASE_PROJECT_ID,
                BuildConfig.FIREBASE_APP_ID,
                BuildConfig.FIREBASE_API_KEY,
                BuildConfig.FIREBASE_STORAGE_BUCKET,
            ),
    ): FirebaseApp? {
        if (!configuration.isComplete()) return null
        FirebaseApp.getApps(context).firstOrNull()?.let {
            return it
        }
        return FirebaseApp.initializeApp(
            context.applicationContext,
            FirebaseOptions.Builder()
                .setProjectId(configuration.projectId)
                .setApplicationId(configuration.applicationId)
                .setApiKey(configuration.apiKey)
                .setStorageBucket(configuration.storageBucket)
                .build(),
        )
    }
}
