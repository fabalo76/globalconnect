package one.globalconnect.pinpad.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Size
import android.view.Surface
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.Result
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import one.globalconnect.pinpad.R
import one.globalconnect.pinpad.logging.PinpadTraceLog
import one.globalconnect.pinpad.protocol.CameraFacing
import one.globalconnect.pinpad.protocol.PhotoCaptureResult
import one.globalconnect.pinpad.protocol.QrDisplayResult
import one.globalconnect.pinpad.protocol.QrScanResult
import java.io.File
import java.util.EnumMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun PhotoCapturePrompt(state: PinpadDisplayState.PhotoCapture) {
    val context = LocalContext.current
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        if (!granted) PinpadDisplayController.completePhotoCapture(PhotoCaptureResult.Error)
    }
    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!permissionGranted) {
        CameraPermissionPrompt()
        return
    }

    var capturedBytes by remember(state) { mutableStateOf<ByteArray?>(null) }
    var capturedBitmap by remember(state) { mutableStateOf<Bitmap?>(null) }
    var captureInProgress by remember(state) { mutableStateOf(false) }
    var cameraError by remember(state) { mutableStateOf<String?>(null) }
    var remainingSeconds by remember(state) { mutableIntStateOf(state.timeoutSeconds) }
    val imageCapture = remember(state) {
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(720, 1280),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                ),
            )
            .build()
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(state.jpegQuality)
            .setResolutionSelector(resolutionSelector)
            .build()
    }

    LaunchedEffect(state) {
        while (remainingSeconds > 0) {
            kotlinx.coroutines.delay(1_000)
            remainingSeconds--
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.photo_capture_title),
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.visual_operation_timeout, remainingSeconds.coerceAtLeast(0)),
            color = Color(0xFFC8CDD5),
            fontSize = 12.sp,
        )
        Box(
            modifier = Modifier
                .width(154.dp)
                .aspectRatio(9f / 16f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black)
                .border(2.dp, Color(0xFF87CEFA), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val bytes = capturedBytes
            val bitmap = capturedBitmap
            if (bytes != null && bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.photo_capture_preview),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                CameraPreview(
                    facing = state.facing,
                    useCases = arrayOf(imageCapture),
                    scaleType = PreviewView.ScaleType.FIT_CENTER,
                    onError = { error -> cameraError = error.message },
                )
            }
        }
        cameraError?.let {
            Text(
                text = it,
                color = Color(0xFFFF8A80),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (capturedBytes == null) {
                Button(
                    onClick = {
                        captureInProgress = true
                        cameraError = null
                        val output = File.createTempFile("pinpad-photo-", ".jpg", context.cacheDir)
                        val options = ImageCapture.OutputFileOptions.Builder(output).build()
                        imageCapture.takePicture(
                            options,
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                    runCatching { output.readBytes() }
                                        .onSuccess { bytes ->
                                            capturedBytes = bytes
                                            capturedBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                        }
                                        .onFailure { error -> cameraError = error.message }
                                    output.delete()
                                    captureInProgress = false
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    output.delete()
                                    captureInProgress = false
                                    cameraError = exception.message
                                    PinpadTraceLog.device("photo capture camera error=${exception.message}")
                                }
                            },
                        )
                    },
                    enabled = !captureInProgress && cameraError == null,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                ) {
                    Text(if (captureInProgress) stringResource(R.string.photo_capturing) else stringResource(R.string.photo_capture))
                }
            } else {
                Button(
                    onClick = {
                        capturedBytes = null
                        capturedBitmap = null
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.photo_retake))
                }
                Button(
                    onClick = {
                        val bytes = capturedBytes ?: return@Button
                        PinpadDisplayController.completePhotoCapture(PhotoCaptureResult.Captured(bytes))
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                ) {
                    Text(stringResource(R.string.photo_use))
                }
            }
            Button(
                onClick = {
                    PinpadDisplayController.completePhotoCapture(PhotoCaptureResult.Cancelled)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

@Composable
fun QrDisplayPrompt(state: PinpadDisplayState.QrDisplay) {
    var remainingSeconds by remember(state) { mutableIntStateOf(state.timeoutSeconds) }
    val qrBitmap = remember(state.value) { createQrBitmap(state.value) }
    LaunchedEffect(state) {
        while (remainingSeconds > 0) {
            kotlinx.coroutines.delay(1_000)
            remainingSeconds--
        }
    }
    if (qrBitmap == null) {
        LaunchedEffect(state) {
            PinpadDisplayController.completeQrDisplay(QrDisplayResult.Error)
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.qr_display_title),
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.visual_operation_timeout, remainingSeconds.coerceAtLeast(0)),
            color = Color(0xFFC8CDD5),
            fontSize = 12.sp,
        )
        Image(
            bitmap = qrBitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.qr_display_title),
            modifier = Modifier
                .height(245.dp)
                .fillMaxWidth()
                .background(Color.White)
                .padding(12.dp),
            contentScale = ContentScale.Fit,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = { PinpadDisplayController.completeQrDisplay(QrDisplayResult.Completed) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
            ) {
                Text(stringResource(R.string.action_done))
            }
            Button(
                onClick = { PinpadDisplayController.completeQrDisplay(QrDisplayResult.Cancelled) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

@Composable
fun QrScanPrompt(state: PinpadDisplayState.QrScan) {
    val context = LocalContext.current
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        if (!granted) PinpadDisplayController.completeQrScan(QrScanResult.Error)
    }
    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    if (!permissionGranted) {
        CameraPermissionPrompt()
        return
    }

    var remainingSeconds by remember(state) { mutableIntStateOf(state.timeoutSeconds) }
    var cameraError by remember(state) { mutableStateOf<String?>(null) }
    val analyzerExecutor = remember(state) { Executors.newSingleThreadExecutor() }
    val completed = remember(state) { AtomicBoolean(false) }
    val analysis = remember(state) {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { useCase ->
                val reader = MultiFormatReader().apply {
                    setHints(
                        EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
                            put(DecodeHintType.POSSIBLE_FORMATS, listOf(BarcodeFormat.QR_CODE))
                            put(DecodeHintType.TRY_HARDER, true)
                        },
                    )
                }
                useCase.setAnalyzer(analyzerExecutor) { image ->
                    try {
                        val result = decodeQrImage(image, reader)
                        if (result != null && completed.compareAndSet(false, true)) {
                            ContextCompat.getMainExecutor(context).execute {
                                PinpadDisplayController.completeQrScan(QrScanResult.Scanned(result.text))
                            }
                        }
                    } finally {
                        image.close()
                    }
                }
            }
    }
    DisposableEffect(state) {
        onDispose {
            analysis.clearAnalyzer()
            analyzerExecutor.shutdownNow()
        }
    }
    LaunchedEffect(state) {
        while (remainingSeconds > 0) {
            kotlinx.coroutines.delay(1_000)
            remainingSeconds--
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.qr_scan_title),
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.visual_operation_timeout, remainingSeconds.coerceAtLeast(0)),
            color = Color(0xFFC8CDD5),
            fontSize = 12.sp,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black)
                .border(2.dp, Color(0xFF87CEFA), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            CameraPreview(
                facing = state.facing,
                useCases = arrayOf(analysis),
                onError = { error -> cameraError = error.message },
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.68f)
                    .height(170.dp)
                    .border(3.dp, Color(0xFF90F0B0), RoundedCornerShape(12.dp)),
            )
        }
        cameraError?.let {
            Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp, textAlign = TextAlign.Center)
        }
        Button(
            onClick = {
                if (completed.compareAndSet(false, true)) {
                    PinpadDisplayController.completeQrScan(QrScanResult.Cancelled)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
        ) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}

@Composable
private fun CameraPreview(
    facing: CameraFacing,
    useCases: Array<UseCase>,
    scaleType: PreviewView.ScaleType = PreviewView.ScaleType.FILL_CENTER,
    onError: (Throwable) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            this.scaleType = scaleType
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize(),
    )

    DisposableEffect(facing, *useCases) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false
        future.addListener({
            if (disposed) return@addListener
            runCatching {
                provider = future.get()
                val targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
                useCases.forEach { useCase ->
                    when (useCase) {
                        is ImageCapture -> useCase.targetRotation = targetRotation
                        is ImageAnalysis -> useCase.targetRotation = targetRotation
                    }
                }
                val preview = Preview.Builder()
                    .setTargetRotation(targetRotation)
                    .build()
                    .also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val desired = facing.selector()
                val fallback = if (facing == CameraFacing.Front) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }
                val selected = when {
                    provider?.hasCamera(desired) == true -> desired
                    provider?.hasCamera(fallback) == true -> fallback
                    else -> throw IllegalStateException("No usable camera is available")
                }
                provider?.unbindAll()
                provider?.bindToLifecycle(lifecycleOwner, selected, preview, *useCases)
            }.onFailure { error ->
                PinpadTraceLog.device("camera start failed=${error.message}")
                onError(error)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            provider?.unbindAll()
        }
    }
}

@Composable
private fun CameraPermissionPrompt() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.camera_permission_required),
            color = Color(0xFFFFD166),
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
        )
    }
}

private fun CameraFacing.selector(): CameraSelector = when (this) {
    CameraFacing.Front -> CameraSelector.DEFAULT_FRONT_CAMERA
    CameraFacing.Back -> CameraSelector.DEFAULT_BACK_CAMERA
}

private fun createQrBitmap(value: String): Bitmap? = try {
    val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
        put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
        put(EncodeHintType.MARGIN, 2)
        put(EncodeHintType.CHARACTER_SET, Charsets.UTF_8.name())
    }
    QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 720, 720, hints).toBitmap()
} catch (_: WriterException) {
    null
}

private fun BitMatrix.toBitmap(): Bitmap {
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val offset = y * width
        for (x in 0 until width) {
            pixels[offset + x] = if (get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

private fun decodeQrImage(
    image: androidx.camera.core.ImageProxy,
    reader: MultiFormatReader,
): Result? {
    val plane = image.planes.firstOrNull() ?: return null
    val width = image.width
    val height = image.height
    if (plane.pixelStride != 1) return null
    val buffer = plane.buffer
    buffer.rewind()
    val raw = ByteArray(buffer.remaining())
    buffer.get(raw)
    val luminance = if (plane.rowStride == width) {
        raw
    } else {
        ByteArray(width * height).also { compact ->
            for (row in 0 until height) {
                val sourceOffset = row * plane.rowStride
                if (sourceOffset + width <= raw.size) {
                    raw.copyInto(compact, row * width, sourceOffset, sourceOffset + width)
                }
            }
        }
    }
    val source = PlanarYUVLuminanceSource(
        luminance,
        width,
        height,
        0,
        0,
        width,
        height,
        false,
    )
    return runCatching {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
    }.getOrNull().also {
        reader.reset()
    }
}
