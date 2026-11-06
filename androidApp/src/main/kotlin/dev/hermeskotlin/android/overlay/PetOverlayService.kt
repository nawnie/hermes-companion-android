package dev.hermeskotlin.android.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.res.Configuration
import dev.hermeskotlin.ui.pet.PetOverlayArtwork
import dev.hermeskotlin.ui.pet.Artwork
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import dev.hermeskotlin.android.HeraldActivity
import dev.hermeskotlin.android.R
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/** A foreground service owns the floating window and removes it when stopped. */
class PetOverlayService : Service() {
    private lateinit var windows: WindowManager
    private var pet: SpriteView? = null
    private var sign: View? = null
    private val screenState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> pet?.pauseAnimation()
                Intent.ACTION_SCREEN_ON -> pet?.resumeAnimation()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        windows = getSystemService(WindowManager::class.java)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Floating pet", NotificationManager.IMPORTANCE_LOW))
        startForeground(7315, notification())
        ContextCompat.registerReceiver(
            this, screenState,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        showPet()
        return START_STICKY
    }

    private fun showPet() {
        pet?.let { runCatching { windows.removeView(it) } }
        pet = null
        sign?.let { runCatching { windows.removeView(it) } }
        sign = null
        val artwork = PetOverlayArtwork.read(this) ?: run { stopSelf(); return }
        val bitmap = BitmapFactory.decodeByteArray(artwork.bytes, 0, artwork.bytes.size) ?: run { stopSelf(); return }
        if (bitmap.width < artwork.width || bitmap.height < artwork.height) { stopSelf(); return }
        val prefs = OverlayPrefs(this)
        val height = (prefs.heightDp * resources.displayMetrics.density).roundToInt()
        val width = (height.toFloat() * artwork.width / artwork.height).roundToInt().coerceAtLeast(1)
        val screenWidth = if (Build.VERSION.SDK_INT >= 30) windows.currentWindowMetrics.bounds.width() else resources.displayMetrics.widthPixels
        val params = WindowManager.LayoutParams(
            width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = ((screenWidth - width).coerceAtLeast(0) * prefs.position).roundToInt()
            y = 0
            alpha = prefs.opacity.coerceAtMost(.8f)
        }
        val view = SpriteView(artwork, bitmap)
        val density = resources.displayMetrics.density
        val signWidth = (80 * density).roundToInt()
        val signHeight = (40 * density).roundToInt()
        val signParams = WindowManager.LayoutParams(
            signWidth, signHeight, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = params.x.coerceAtMost((screenWidth - signWidth).coerceAtLeast(0))
            y = params.height
        }
        val label = TextView(this).apply {
            text = "Hermes"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            contentDescription = "Open Hermes chat. Long press for pet appearance."
            background = GradientDrawable().apply {
                setColor(Color.rgb(40, 35, 58))
                cornerRadius = 18 * density
                setStroke((1 * density).roundToInt().coerceAtLeast(1), Color.rgb(171, 148, 222))
            }
            setOnClickListener {
                val launch = Intent(this@PetOverlayService, HeraldActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                runCatching { startActivity(launch) }
            }
            setOnLongClickListener {
                val launch = Intent(this@PetOverlayService, PetOverlayActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { startActivity(launch) }
                true
            }
        }
        try {
            windows.addView(view, params)
            pet = view
            windows.addView(label, signParams)
            sign = label
        } catch (_: RuntimeException) {
            stopSelf()
        }
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, PetOverlayActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, PetOverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Floating pet is on")
            .setContentText("Tap the Hermes sign for chat; hold it for appearance")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Turn off", stop).build())
            .build()
    }

    override fun onDestroy() {
        unregisterReceiver(screenState)
        pet?.let { runCatching { windows.removeView(it) } }
        pet = null
        sign?.let { runCatching { windows.removeView(it) } }
        sign = null
        running = false
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Settings.canDrawOverlays(this) && pet != null) showPet()
    }

    private inner class SpriteView(private val art: Artwork, private val bitmap: android.graphics.Bitmap) : View(this) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val handler = Handler(Looper.getMainLooper())
        private var frame = 0
        private val tick = object : Runnable {
            override fun run() {
                val columns = (bitmap.width / art.width).coerceAtLeast(1)
                frame = (frame + 1) % art.frames.coerceIn(1, columns)
                invalidate()
                handler.postDelayed(this, (art.loop / art.frames.coerceAtLeast(1)).coerceAtLeast(40).toLong())
            }
        }
        fun pauseAnimation() { handler.removeCallbacks(tick) }
        fun resumeAnimation() {
            handler.removeCallbacks(tick)
            if (isAttachedToWindow && getSystemService(PowerManager::class.java).isInteractive) handler.post(tick)
        }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); resumeAnimation() }
        override fun onDetachedFromWindow() { pauseAnimation(); super.onDetachedFromWindow() }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val row = if ((art.row + 1).toLong() * art.height <= bitmap.height) art.row else 0
            val source = Rect(frame * art.width, row * art.height, (frame + 1) * art.width, (row + 1) * art.height)
            canvas.drawBitmap(bitmap, source, Rect(0, 0, width, height), paint)
        }
    }

    companion object {
        @Volatile var running: Boolean = false
            private set
        const val ACTION_STOP = "dev.hermeskotlin.android.overlay.STOP"
        private const val CHANNEL = "pet_overlay"
    }
}





