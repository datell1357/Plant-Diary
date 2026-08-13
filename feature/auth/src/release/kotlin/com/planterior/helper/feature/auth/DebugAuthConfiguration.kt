package com.planterior.helper.feature.auth

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.Composable

@Composable fun DebugAuthControls(onGoogle: () -> Unit, onApple: () -> Unit) = Unit

fun prepareDebugAuth(activity: Activity) = Unit

fun debugAuthProvider(context: Context, delegate: AuthProviderAdapter): AuthProviderAdapter =
    delegate

fun debugAccountSyncRemote(context: Context, delegate: AccountSyncRemote): AccountSyncRemote =
    delegate
