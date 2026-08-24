package com.planterior.helper.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

@Composable
fun AccountDeletionRoute(
    dependencies: AccountDeletionDependencies,
    onBack: () -> Unit,
) {
    val controller =
        viewModel<AccountDeletionController>(
            factory = viewModelFactory { initializer { AccountDeletionController(dependencies) } }
        )
    val state by controller.state.collectAsState()
    AccountDeletionScreen(
        state = state,
        actions =
            AccountDeletionActions(
                onReauthenticate = controller::reauthenticate,
                onFinalConfirmationChanged = controller::setFinalConfirmation,
                onSubmit = controller::submit,
                onCancel = controller::cancel,
                onRefresh = controller::refresh,
            ),
        onBack = onBack,
    )
}
