package dev.hermeskotlin.android.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import dev.hermeskotlin.core.phone.PhoneToolException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

/** Android accessibility service for the screen-control tool. Android controls whether it is enabled. */
class ScreenControlService : AccessibilityService() {
    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onInterrupt() = Unit
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    suspend fun gesture(action: String, x: Int, y: Int, endX: Int, endY: Int, duration: Int) = withContext(Dispatchers.Main) {
        if (instance !== this@ScreenControlService) throw PhoneToolException("Screen control is not connected.")
        if (action == "back" || action == "home") {
            if (!performGlobalAction(if (action == "back") GLOBAL_ACTION_BACK else GLOBAL_ACTION_HOME))
                throw PhoneToolException("Android refused the navigation action.")
            return@withContext
        }
        val metrics = resources.displayMetrics
        val path = Path().apply {
            moveTo(x * (metrics.widthPixels - 1) / 10000f, y * (metrics.heightPixels - 1) / 10000f)
            if (action == "swipe") lineTo(endX * (metrics.widthPixels - 1) / 10000f, endY * (metrics.heightPixels - 1) / 10000f)
        }
        val done = CompletableDeferred<Boolean>()
        val accepted = dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration.toLong())).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) { done.complete(true) }
                override fun onCancelled(gestureDescription: GestureDescription) { done.complete(false) }
            }, null)
        if (!accepted || withTimeoutOrNull(3000) { done.await() } != true) throw PhoneToolException("Android did not complete the touch action.")
    }

    /** A compact screenshot that fits the gateway tool response. */
    suspend fun screenshot(): Triple<String, Int, Int> = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) throw PhoneToolException("Screen inspection needs Android 11 or newer.")
        if (instance !== this@ScreenControlService) throw PhoneToolException("Screen control is not connected.")
        val done = CompletableDeferred<Triple<String, Int, Int>?>()
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                try {
                    val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: run { done.complete(null); return }
                    val bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false)
                    val width = minOf(bitmap.width, 360)
                    val height = (bitmap.height.toLong() * width / bitmap.width).toInt().coerceAtLeast(1)
                    val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
                    var encoded: ByteArray? = null
                    for (quality in listOf(55, 40, 25, 15)) {
                        val bytes = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
                        if (bytes.size <= 39000) { encoded = bytes; break }
                    }
                    if (scaled !== bitmap) scaled.recycle()
                    bitmap.recycle(); hardware.recycle()
                    done.complete(encoded?.let { Triple(Base64.encodeToString(it, Base64.NO_WRAP), width, height) })
                } catch (_: Exception) { done.complete(null) } finally { buffer.close() }
            }
            override fun onFailure(errorCode: Int) { done.complete(null) }
        })
        withTimeoutOrNull(5000) { done.await() } ?: throw PhoneToolException("Android could not capture this screen.")
    }

    companion object { @Volatile var instance: ScreenControlService? = null; private set }
}
