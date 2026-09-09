package net.chess99.nasfind

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Downsample untrusted images before allocating their pixel buffer; QR reading stays entirely local. */
fun readConnectionImage(context: Context, uri: Uri): SharedConnection {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取图片，请选择包含完整二维码的图片" }
    val options = BitmapFactory.Options().apply {
        inSampleSize = 1
        while (bounds.outWidth / inSampleSize > 2048 || bounds.outHeight / inSampleSize > 2048) inSampleSize *= 2
    }
    val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: throw IllegalArgumentException("无法读取图片")
    val text = try {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8")
        runCatching { QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text }
            .recoverCatching { QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source.invert())), hints).text }
            .getOrElse { throw IllegalArgumentException("图片中未找到二维码，请选择清晰完整的二维码图片") }
    } finally { bitmap.recycle() }
    return ConnectionTransfer.decode(text)
}

@Composable fun ConnectionScanner(onClose: () -> Unit, onRead: (SharedConnection) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var camera by remember { mutableStateOf<BarcodeView?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var readingImage by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var received by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    val latestRead by rememberUpdatedState(onRead)
    val requestCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; permissionDenied = !it }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            readingImage = true; error = null
            scope.launch {
                try { val config = withContext(Dispatchers.IO) { readConnectionImage(context, uri) }; received = true; latestRead(config) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: SecurityException) { error = "无法访问这张图片，请重新选择" }
                catch (e: IllegalArgumentException) { error = e.message }
                catch (_: Exception) { error = "无法识别图片，请选择清晰完整的连接二维码" }
                finally { readingImage = false }
            }
        }
    }
    BackHandler(onBack = onClose)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            active = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); camera?.pause() }
    }
    LaunchedEffect(camera, granted, active, error, readingImage, received) {
        if (granted && active && error == null && !readingImage && !received) camera?.resume() else camera?.pause()
    }
    Column(Modifier.fillMaxSize().testTag("connection-scanner")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回连接设置") }
            Text("扫码导入", Modifier.weight(1f), fontSize = 20.sp)
            TextButton(onClick = { pickImage.launch("image/*") }, enabled = !readingImage, modifier = Modifier.testTag("import-image")) {
                Icon(Icons.Outlined.Image, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("从图片导入")
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF17251E)), contentAlignment = Alignment.Center) {
            if (granted) {
                AndroidView(factory = { current ->
                    BarcodeView(current).apply {
                        decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                        addStateListener(object : CameraPreview.StateListener {
                            override fun previewSized() = Unit
                            override fun previewStarted() = Unit
                            override fun previewStopped() = Unit
                            override fun cameraClosed() = Unit
                            override fun cameraError(cause: Exception) { error = "相机暂时无法使用，可从图片导入" }
                        })
                        decodeContinuous(object : BarcodeCallback {
                            override fun barcodeResult(result: BarcodeResult) {
                                if (received || error != null || readingImage || !active) return
                                try { val config = ConnectionTransfer.decode(result.text); received = true; latestRead(config) }
                                catch (e: IllegalArgumentException) { error = e.message }
                            }
                        })
                        camera = this
                    }
                }, modifier = Modifier.fillMaxSize(), onRelease = { it.pause() })
                Canvas(Modifier.fillMaxWidth(.72f).aspectRatio(1f)) {
                    val arm = 24.dp.toPx(); val stroke = 3.dp.toPx(); val green = Color(0xFF9DDBB9)
                    for (corner in listOf(Offset.Zero, Offset(size.width, 0f), Offset(0f, size.height), Offset(size.width, size.height))) {
                        val dx = if (corner.x == 0f) arm else -arm; val dy = if (corner.y == 0f) arm else -arm
                        drawLine(green, corner, corner + Offset(dx, 0f), strokeWidth = stroke)
                        drawLine(green, corner, corner + Offset(0f, dy), strokeWidth = stroke)
                    }
                }
            } else Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(52.dp), tint = Color(0xFF9DDBB9))
                Spacer(Modifier.height(20.dp)); Text("使用相机扫描连接二维码", color = Color.White)
                Text(if (permissionDenied) "相机权限未开启，仍可从图片导入" else "也可以直接从图片导入", color = Color(0xFFB5C7BA), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
                Button(onClick = { requestCamera.launch(Manifest.permission.CAMERA) }, modifier = Modifier.testTag("allow-camera")) { Text("启用相机") }
            }
            if (readingImage) CircularProgressIndicator(color = Color.White)
        }
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (error != null) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { error = null }) { Text("继续扫码") }
            } else {
                Text("在电脑的连接设置中打开“分享连接配置”", fontSize = 14.sp)
                Text("识别后确认服务地址，再连接 NAS", modifier = Modifier.padding(top = 6.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
