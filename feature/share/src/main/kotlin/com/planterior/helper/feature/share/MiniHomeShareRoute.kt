package com.planterior.helper.feature.share

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.planterior.helper.feature.minihome.MiniHomeAuthOwnership
import com.planterior.helper.feature.minihome.MiniHomePhotoLoader
import com.planterior.helper.feature.minihome.PlaceholderMiniHomePhotoLoader
import kotlinx.coroutines.launch

private class MiniHomeShareViewModel(val controller: MiniHomeShareController) : ViewModel()

/**
 * 미니홈 공유 화면 진입점이다.
 *
 * 화면에 들어올 때마다 권위 있는 확정 구성을 다시 읽고, 편집 중 draft는 어떤 경로로도 받지 않는다. 계정이 바뀌면 진행 중 작업을 버리고 로컬 산출물을 지운다.
 */
@Composable
fun MiniHomeShareRoute(
    repository: MiniHomeShareRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    photoLoader: MiniHomePhotoLoader = PlaceholderMiniHomePhotoLoader,
    authOwnership: MiniHomeAuthOwnership = MiniHomeAuthOwnership.Unmanaged,
) {
    val context = LocalContext.current
    val model =
        viewModel<MiniHomeShareViewModel>(
            factory =
                viewModelFactory {
                    initializer { MiniHomeShareViewModel(MiniHomeShareController(repository)) }
                }
        )
    val controller = model.controller
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val imageStore = remember(context) { MiniHomeShareImageStore(context) }
    val clipboard = remember(context) { MiniHomeShareClipboard(context) }
    val handle = rememberMiniHomeShareCaptureHandle()
    var showsInAppCopyFeedback by remember { mutableStateOf(true) }
    val captureToken = controller.captureToken()
    val currentToken by rememberUpdatedState(captureToken)

    val sheetLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result
            ->
            // chooser는 무엇이 전달됐는지 알려주지 않는다. 취소만 구분하고 나머지는 "열렸다"로만 옮긴다.
            controller.onSheetOutcome(miniHomeShareSheetOutcome(result.resultCode))
        }

    val exporter =
        remember(handle, imageStore, sheetLauncher) {
            MiniHomeShareImageExporter(
                sink = imageStore,
                recorder = handle,
                // 매 중단점 이후 현재 소유자와 확정 revision을 다시 확인한다.
                isCurrent = { token -> controller.isCurrent(token) },
                hasTarget = { uri ->
                    MiniHomeShareSheet.hasTarget(context, MiniHomeShareSheet.imageIntent(uri))
                },
                launch = { intent ->
                    sheetLauncher.launch(MiniHomeShareSheet.chooser(context, intent))
                },
            )
        }

    LaunchedEffect(controller, authOwnership) { controller.start(authOwnership) }

    // 레이어가 실제로 기록됐다는 정확한 신호를 받은 뒤에만 준비 완료로 넘어간다.
    LaunchedEffect(captureToken) {
        val token = captureToken ?: return@LaunchedEffect
        handle.signal.awaitRecorded(token)
        controller.onRecorded(token)
    }

    DisposableEffect(controller) {
        onDispose {
            // 화면을 떠나면 기록 신호를 무효화하고 URI 권한이 걸린 파일도 남기지 않는다.
            handle.signal.invalidate()
            imageStore.clear()
        }
    }

    MiniHomeShareScreen(
        state = state,
        onBack = onBack,
        onCreateLink = { scope.launch { controller.createLink() } },
        onCopyLink = {
            val active =
                (state as? MiniHomeShareUiState.Ready)?.link as? MiniHomeShareLinkState.Active
            if (active != null) {
                showsInAppCopyFeedback = clipboard.copy(active.link.url)
                controller.onLinkCopied()
            }
        },
        onShareImage = {
            val token = currentToken
            if (token != null) {
                scope.launch {
                    when (exporter.export(token)) {
                        MiniHomeShareExportOutcome.Launched -> Unit
                        MiniHomeShareExportOutcome.Stale ->
                            controller.onSheetOutcome(MiniHomeShareSheetOutcome.Stale)
                        MiniHomeShareExportOutcome.NoTarget ->
                            controller.onSheetOutcome(MiniHomeShareSheetOutcome.NoTarget)
                        MiniHomeShareExportOutcome.Failed ->
                            controller.onSheetOutcome(MiniHomeShareSheetOutcome.Failed)
                    }
                }
            }
        },
        onShareLink = {
            val active =
                (state as? MiniHomeShareUiState.Ready)?.link as? MiniHomeShareLinkState.Active
            if (active != null) {
                val payload = MiniHomeShareSheet.linkIntent(active.link.url)
                if (!MiniHomeShareSheet.hasTarget(context, payload)) {
                    controller.onSheetOutcome(MiniHomeShareSheetOutcome.NoTarget)
                } else {
                    runCatching {
                        sheetLauncher.launch(MiniHomeShareSheet.chooser(context, payload))
                    }
                        .onFailure {
                            controller.onSheetOutcome(MiniHomeShareSheetOutcome.Failed)
                        }
                }
            }
        },
        onRevokeLink = { scope.launch { controller.revokeLink() } },
        onRetryRender = {
            handle.signal.invalidate()
            controller.retryRender()
        },
        onRetryLoad = { scope.launch { controller.retryLoad(authOwnership) } },
        modifier = modifier,
        photoLoader = photoLoader,
        captureHandle = handle,
        captureToken = captureToken,
        showsInAppCopyFeedback = showsInAppCopyFeedback,
    )
}
