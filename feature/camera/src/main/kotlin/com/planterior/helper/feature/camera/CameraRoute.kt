package com.planterior.helper.feature.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.planterior.helper.core.model.ProductEventRecorder
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Activity Result, CameraX, URI 검증을 제품 상태 흐름에 연결한다. */
@Composable
fun CameraRoute(
    onExit: () -> Unit,
    onDirectRegistration: () -> Unit,
    onIdentificationRequested: suspend (PhotoSubmission) -> Unit,
    modifier: Modifier = Modifier,
    productEventRecorder: ProductEventRecorder = ProductEventRecorder {},
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val store = remember(context) { PrivatePhotoStore(context) }
    val preparer =
        remember(context, store) {
            PhotoPreparer(
                PhotoValidator(ContentResolverPhotoUriReader(context.contentResolver)),
                store,
            )
        }
    val scope = rememberCoroutineScope()
    val commandSink = remember { CameraCommandSink() }
    val holder = remember { CameraControllerHolder() }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val controller = holder.controller ?: return@rememberLauncherForActivityResult
            if (granted) {
                controller.chooseCamera(CameraPermission.Granted)
            } else {
                controller.cameraPermissionDenied(
                    permanently =
                        activity?.shouldShowRequestPermissionRationale(
                            Manifest.permission.CAMERA
                        ) != true
                )
            }
        }
    val pickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val controller = holder.controller ?: return@rememberLauncherForActivityResult
            if (uri == null) {
                controller.pickerCancelled()
            } else {
                controller.captureStarted()
                scope.launch {
                    val result =
                        withContext(Dispatchers.IO) {
                            preparer.prepare(uri.toString(), PhotoSource.Picker)
                        }
                    result.fold(
                        onSuccess = controller::photoPrepared,
                        onFailure = { controller.photoRejected(it.photoError()) },
                    )
                }
            }
        }

    val controller =
        rememberSaveable(
            saver =
                controllerSaver(
                    temporaryUriFactory = TemporaryUriFactory(store::allocate),
                    requestIdFactory = RequestIdFactory { UUID.randomUUID().toString() },
                    gateway = IdentificationGateway(onIdentificationRequested),
                    launch = commandSink::emit,
                    discard = store::delete,
                    productEventRecorder = productEventRecorder,
                )
        ) {
            CameraFlowController(
                temporaryUriFactory = TemporaryUriFactory(store::allocate),
                requestIdFactory = RequestIdFactory { UUID.randomUUID().toString() },
                clock = Clock.systemUTC(),
                gateway = IdentificationGateway(onIdentificationRequested),
                launch = commandSink::emit,
                discard = store::delete,
                productEventRecorder = productEventRecorder,
            )
        }
    holder.controller = controller
    commandSink.consumer = { command ->
        when (command) {
            CameraCommand.RequestPermission -> permissionLauncher.launch(Manifest.permission.CAMERA)
            CameraCommand.LaunchPhotoPicker -> {
                val debugUri = todo18DebugPhotoPickerUri()
                val token = todo18DebugStartCameraTrace(debugUri)
                if (debugUri == null) {
                    pickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                } else {
                    holder.controller?.let { controller ->
                        controller.captureStarted()
                        todo18DebugTraceCameraStage(
                            token,
                            Todo18DebugCameraTraceStage.COROUTINE_SCHEDULED,
                            debugUri,
                        )
                        scope.launch {
                            todo18DebugTraceCameraStage(
                                token,
                                Todo18DebugCameraTraceStage.COROUTINE_ENTERED,
                                debugUri,
                            )
                            todo18DebugObservePhotoPreparation(token, debugUri) {
                                todo18DebugTraceCameraStage(
                                    token,
                                    Todo18DebugCameraTraceStage.PREPARE_ENTERED,
                                    debugUri,
                                )
                                val result =
                                    withContext(Dispatchers.IO) {
                                        preparer.prepare(debugUri, PhotoSource.Picker)
                                    }
                                todo18DebugTraceCameraStage(
                                    token,
                                    Todo18DebugCameraTraceStage.PREPARE_RETURNED,
                                    debugUri,
                                )
                                result.fold(
                                    onSuccess = controller::photoPrepared,
                                    onFailure = { controller.photoRejected(it.photoError()) },
                                )
                                todo18DebugTraceCameraStage(
                                    token,
                                    Todo18DebugCameraTraceStage.FOLD_RETURNED,
                                    debugUri,
                                )
                                result.isSuccess
                            }
                        }
                    }
                }
            }
            CameraCommand.OpenAppSettings ->
                context.startActivity(
                    Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        )
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            CameraCommand.OpenDirectRegistration -> onDirectRegistration()
            is CameraCommand.LaunchCamera -> Unit
        }
    }

    val state = controller.state
    val photo = state.draft
    val preview by producePreview(context, photo)
    val captureSession = remember { CameraCaptureSession() }
    BackHandler {
        when (state) {
            is CameraFlowState.Source,
            is CameraFlowState.PermissionBlocked,
            is CameraFlowState.Submitted -> {
                controller.exit()
                onExit()
            }
            else -> controller.back()
        }
    }
    CameraScreen(
        state = state,
        preview = preview,
        onCamera = {
            controller.chooseCamera(todo18DebugCameraPermission(context.currentCameraPermission()))
        },
        onPicker = controller::choosePicker,
        onDirect = controller::chooseDirectRegistration,
        onSettings = controller::openSettings,
        onCapture = captureSession::capture,
        onCloseCapture = controller::captureCancelled,
        onReplace = controller::replacePhoto,
        onRetake = {
            controller.retakePhoto(todo18DebugCameraPermission(context.currentCameraPermission()))
        },
        onSubmit = controller::requestIdentification,
        onApprove = { scope.launch { controller.approveDisclosure() } },
        onCancelDisclosure = controller::cancelDisclosure,
        onBack = {
            when (state) {
                is CameraFlowState.Source,
                is CameraFlowState.PermissionBlocked,
                is CameraFlowState.Submitted -> {
                    controller.exit()
                    onExit()
                }
                else -> controller.back()
            }
        },
        modifier = modifier,
        cameraPreview =
            (state as? CameraFlowState.Capturing)?.let { capturing ->
                { previewModifier ->
                    CameraXPreview(
                        outputUri = capturing.temporaryUri,
                        session = captureSession,
                        onResult = { result ->
                            controller.captureStarted()
                            result.fold(
                                onSuccess = {
                                    scope.launch {
                                        val prepared =
                                            withContext(Dispatchers.IO) {
                                                preparer.prepare(it, PhotoSource.Camera)
                                            }
                                        prepared.fold(
                                            onSuccess = controller::photoPrepared,
                                            onFailure = { error ->
                                                controller.photoRejected(error.photoError())
                                            },
                                        )
                                    }
                                },
                                onFailure = {
                                    store.delete(capturing.temporaryUri)
                                    controller.photoRejected(PhotoError.CaptureFailed)
                                },
                            )
                        },
                        modifier = previewModifier,
                    )
                }
            },
    )
}

private class CameraCommandSink {
    var consumer: (CameraCommand) -> Unit = {}

    fun emit(command: CameraCommand) = consumer(command)
}

private class CameraControllerHolder {
    var controller: CameraFlowController? = null
}

private class CameraCaptureSession {
    var action: () -> Unit = {}

    fun capture() = action()
}

@Composable
private fun CameraXPreview(
    outputUri: String,
    session: CameraCaptureSession,
    onResult: (Result<String>) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    AndroidView(
        factory = { PreviewView(it).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } },
        modifier = modifier,
        update = { previewView ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    val cameraProvider = future.get()
                    val preview =
                        Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                    val capture =
                        ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .build()
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        capture,
                    )
                    provider = cameraProvider
                    imageCapture = capture
                },
                executor,
            )
        },
    )
    session.action = {
        val capture = imageCapture
        if (capture == null) {
            onResult(Result.failure(IllegalStateException("Camera is not ready")))
        } else {
            captureToUri(context, capture, outputUri, executor, onResult)
        }
    }
    DisposableEffect(Unit) { onDispose { provider?.unbindAll() } }
}

private fun captureToUri(
    context: Context,
    imageCapture: ImageCapture,
    outputUri: String,
    executor: Executor,
    onResult: (Result<String>) -> Unit,
) {
    val stream = runCatching {
        requireNotNull(context.contentResolver.openOutputStream(outputUri.toUri(), "w"))
    }
        .getOrElse {
            onResult(Result.failure(it))
            return
        }
    imageCapture.takePicture(
        ImageCapture.OutputFileOptions.Builder(stream).build(),
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                stream.close()
                onResult(Result.success(outputUri))
            }

            override fun onError(exception: ImageCaptureException) {
                stream.close()
                onResult(Result.failure(exception))
            }
        },
    )
}

@Composable
private fun producePreview(context: Context, photo: PreparedPhoto?) =
    produceState<CameraPreviewImage?>(initialValue = null, photo) {
        value =
            if (photo == null) null
            else
                withContext(Dispatchers.IO) {
                    val uri = photo.privateUri.toUri()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, bounds)
                    }
                    var sample = 1
                    while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) {
                        sample *= 2
                    }
                    val options = BitmapFactory.Options().apply { inSampleSize = sample }
                    context.contentResolver
                        .openInputStream(uri)
                        ?.use {
                            BitmapFactory.decodeStream(it, null, options)
                        }
                        ?.let { bitmap ->
                            val rotated =
                                bitmap.transformed(
                                    photo.rotationDegrees,
                                    photo.mirroredHorizontally,
                                )
                            CameraPreviewImage(rotated.asImageBitmap(), 0)
                        }
                }
    }

private fun Bitmap.transformed(degrees: Int, mirroredHorizontally: Boolean): Bitmap {
    if (degrees == 0 && !mirroredHorizontally) return this
    val rotated =
        Bitmap.createBitmap(
            this,
            0,
            0,
            width,
            height,
            Matrix().apply {
                if (mirroredHorizontally) postScale(-1f, 1f)
                postRotate(degrees.toFloat())
            },
            true,
        )
    if (rotated !== this) recycle()
    return rotated
}

private fun Context.currentCameraPermission(): CameraPermission =
    if (
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    ) {
        CameraPermission.Granted
    } else {
        CameraPermission.NotRequested
    }

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private fun Throwable.photoError(): PhotoError =
    (this as? PhotoPreparationException)?.photoError ?: PhotoError.Unreadable

private fun controllerSaver(
    temporaryUriFactory: TemporaryUriFactory,
    requestIdFactory: RequestIdFactory,
    gateway: IdentificationGateway,
    launch: (CameraCommand) -> Unit,
    discard: (String) -> Unit,
    productEventRecorder: ProductEventRecorder,
): Saver<CameraFlowController, Bundle> =
    Saver(
        save = { it.snapshot().toBundle() },
        restore = {
            CameraFlowController(
                temporaryUriFactory = temporaryUriFactory,
                requestIdFactory = requestIdFactory,
                clock = Clock.systemUTC(),
                gateway = gateway,
                launch = launch,
                discard = discard,
                restored = CameraFlowSnapshot(it.toCameraState()),
                productEventRecorder = productEventRecorder,
            )
        },
    )

private fun CameraFlowSnapshot.toBundle(): Bundle =
    Bundle().apply {
        putString("kind", state::class.simpleName)
        state.draft?.let(::putPhoto)
        when (val current = state) {
            is CameraFlowState.PermissionBlocked ->
                putBoolean("permanent", current.permanentlyDenied)
            is CameraFlowState.Capturing -> putString("temporary", current.temporaryUri)
            is CameraFlowState.Disclosure -> putString("request", current.requestId)
            is CameraFlowState.Review -> putString("error", current.error.errorName())
            is CameraFlowState.Source -> putString("error", current.error.errorName())
            is CameraFlowState.Submitted -> {
                putString("request", current.submission.requestId)
                putLong("approved", current.submission.approvedAt.toEpochMilli())
            }
            is CameraFlowState.Processing -> Unit
        }
    }

private fun Bundle.putPhoto(photo: PreparedPhoto) {
    putString("photoUri", photo.privateUri)
    putString("photoMime", photo.mime.name)
    putLong("photoBytes", photo.byteSize)
    putInt("photoWidth", photo.width)
    putInt("photoHeight", photo.height)
    putInt("photoRotation", photo.rotationDegrees)
    putString("photoSource", photo.source.name)
    putBoolean("photoMirrored", photo.mirroredHorizontally)
}

private fun Bundle.photo(): PreparedPhoto? {
    val uri = getString("photoUri") ?: return null
    return PreparedPhoto(
        uri,
        PhotoMime.valueOf(requireNotNull(getString("photoMime"))),
        getLong("photoBytes"),
        getInt("photoWidth"),
        getInt("photoHeight"),
        getInt("photoRotation"),
        PhotoSource.valueOf(requireNotNull(getString("photoSource"))),
        getBoolean("photoMirrored"),
    )
}

private fun Bundle.toCameraState(): CameraFlowState {
    val photo = photo()
    return when (getString("kind")) {
        CameraFlowState.PermissionBlocked::class.simpleName ->
            CameraFlowState.PermissionBlocked(getBoolean("permanent"), photo)
        CameraFlowState.Capturing::class.simpleName ->
            CameraFlowState.Capturing(requireNotNull(getString("temporary")), photo)
        CameraFlowState.Processing::class.simpleName -> CameraFlowState.Processing(photo)
        CameraFlowState.Review::class.simpleName ->
            CameraFlowState.Review(requireNotNull(photo), getString("error").photoErrorOrNull())
        CameraFlowState.Disclosure::class.simpleName ->
            CameraFlowState.Disclosure(
                requireNotNull(photo),
                requireNotNull(getString("request")),
                PhotoDisclosure.Product,
            )
        CameraFlowState.Submitted::class.simpleName -> {
            val submission =
                PhotoSubmission(
                    requireNotNull(getString("request")),
                    requireNotNull(photo),
                    PhotoDisclosure.Product,
                    Instant.ofEpochMilli(getLong("approved")),
                )
            CameraFlowState.Submitted(submission)
        }
        else -> CameraFlowState.Source(photo, getString("error").photoErrorOrNull())
    }
}

private fun PhotoError?.errorName(): String? = this?.let { it::class.simpleName }

private fun String?.photoErrorOrNull(): PhotoError? =
    when (this) {
        PhotoError.MissingUri::class.simpleName -> PhotoError.MissingUri
        PhotoError.Unreadable::class.simpleName -> PhotoError.Unreadable
        PhotoError.Corrupt::class.simpleName -> PhotoError.Corrupt
        PhotoError.UnsupportedMime::class.simpleName -> PhotoError.UnsupportedMime
        PhotoError.TooLarge::class.simpleName -> PhotoError.TooLarge
        PhotoError.DimensionsOutOfRange::class.simpleName -> PhotoError.DimensionsOutOfRange
        PhotoError.CaptureFailed::class.simpleName -> PhotoError.CaptureFailed
        PhotoError.SubmissionFailed::class.simpleName -> PhotoError.SubmissionFailed
        else -> null
    }
