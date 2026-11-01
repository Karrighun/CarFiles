package dev.carfiles

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import java.io.ByteArrayOutputStream

/**
 * Захват экрана → JPEG → [ProjectionState] (экран Android Auto) и [FrameServer] (MJPEG по HTTP).
 *
 * Что исправлено относительно 0.3.2:
 *  1. Кадры, пришедшие чаще чем раз в 250 мс, отбрасывались целиком. Если после них экран замирал,
 *     на машине оставалась устаревшая картинка. Теперь копируется каждый кадр, а кодирование идёт
 *     с «хвостом» ([FrameThrottle]): последнее состояние экрана всегда отправляется.
 *  2. Если ни одного кадра не пришло (статичный экран сразу после старта), поверхность
 *     пере-подключается — система тут же присылает кадр. Раньше машина ждала до первого изменения.
 *  3. Bitmap создавался заново на каждый кадр (давление на GC, паузы) → один переиспользуемый буфер.
 *  4. Порт сервера мог быть занят после быстрого перезапуска → см. [FrameServer].
 *  5. Размер берётся из реальных метрик окна, чётные размеры, поворот обрабатывается через
 *     onCapturedContentResize и пересоздание ImageReader.
 *  6. Настройки fps/разрешения/качества задаются с экрана.
 */
class ProjectionService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var server: FrameServer? = null
    private val thread = HandlerThread("CarFiles-capture")
    private lateinit var capture: Handler
    private val main = Handler(Looper.getMainLooper())

    private var width = 0
    private var height = 0
    private var maxSide = 960
    private var quality = 75
    private var throttle = FrameThrottle(1000L / 10)
    private var buffer: Bitmap? = null
    private var dirty = false
    private var encodeScheduled = false
    private var nudges = 0
    private val bytes = ByteArrayOutputStream(256 * 1024)

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { ProjectionState.message = "Трансляция остановлена системой"; stopSelf() }
        override fun onCapturedContentResize(w: Int, h: Int) { if (w > 0 && h > 0) capture.post { configure(w, h) } }
    }

    override fun onCreate() { super.onCreate(); thread.start(); capture = Handler(thread.looper) }
    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (projection != null) return START_NOT_STICKY
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        maxSide = intent.getIntExtra(EXTRA_MAX_SIDE, 960).coerceIn(480, 1920)
        quality = intent.getIntExtra(EXTRA_QUALITY, 75).coerceIn(40, 95)
        throttle = FrameThrottle(1000L / intent.getIntExtra(EXTRA_FPS, 10).coerceIn(1, 30))
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            val mp = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED), data)
            projection = mp
            mp.registerCallback(callback, main)                 // обязательно до createVirtualDisplay (Android 14+)
            ProjectionState.reset()
            val srv = FrameServer().also { server = it }
            ProjectionState.addresses = srv.urls(); ProjectionState.address = srv.address()
            ProjectionState.startedAt = System.currentTimeMillis()
            ProjectionState.running = true
            ProjectionState.message = "Трансляция запущена"
            val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            capture.post { configure(bounds.width(), bounds.height()) }
            capture.postDelayed(::watchdog, 1500)
            CarLog.add(this, "Захват экрана запущен · $maxSide px · ${intent.getIntExtra(EXTRA_FPS, 10)} к/с · порт ${srv.port}")
        } catch (e: Exception) {
            ProjectionState.running = false
            ProjectionState.message = "Не удалось начать трансляцию: ${e.message}"
            CarLog.add(this, "Ошибка запуска захвата: ${e.javaClass.simpleName}: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Трансляция экрана", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, ProjectionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 1, Intent(this, ProjectionActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("CarFiles · трансляция экрана")
            .setContentText("Нажмите «Остановить», чтобы завершить захват")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Остановить", stop).build()).build()
    }

    /** Вызывается только на потоке capture. */
    private fun configure(sourceW: Int, sourceH: Int) {
        val mp = projection ?: return
        val scale = minOf(1.0, maxSide.toDouble() / maxOf(sourceW, sourceH))
        val w = maxOf(2, (sourceW * scale).toInt()) and 1.inv()
        val h = maxOf(2, (sourceH * scale).toInt()) and 1.inv()
        if (w == width && h == height && display != null) return
        width = w; height = h
        val next = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        next.setOnImageAvailableListener(::onImage, capture)
        val old = reader
        reader = next
        buffer?.recycle(); buffer = null
        val dpi = resources.displayMetrics.densityDpi
        val current = display
        if (current == null) {
            display = mp.createVirtualDisplay("CarFiles-screen", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, next.surface, null, capture)
        } else {
            current.resize(w, h, dpi)
            current.surface = next.surface
        }
        old?.close()
        ProjectionState.width = w; ProjectionState.height = h
    }

    private fun onImage(source: ImageReader) {
        val image = try { source.acquireLatestImage() } catch (_: Exception) { null } ?: return
        try {
            val plane = image.planes[0]
            val padded = plane.rowStride / plane.pixelStride
            var buf = buffer
            if (buf == null || buf.width != padded || buf.height != image.height) {
                buf?.recycle()
                buf = Bitmap.createBitmap(padded, image.height, Bitmap.Config.ARGB_8888)
                buffer = buf
            }
            plane.buffer.rewind()
            buf.copyPixelsFromBuffer(plane.buffer)
            dirty = true
        } catch (_: Exception) { /* ImageReader мог закрыться при повороте — следующий кадр придёт из нового */ }
        finally { runCatching { image.close() } }
        scheduleEncode()
    }

    /** Кодирование не чаще заданного fps, но всегда с самым свежим содержимым (trailing edge). */
    private fun scheduleEncode() {
        if (encodeScheduled || !dirty) return
        encodeScheduled = true
        capture.postDelayed(::encode, throttle.delayFor(SystemClock.elapsedRealtime()))
    }

    private fun encode() {
        encodeScheduled = false
        val buf = buffer
        if (!dirty || buf == null || projection == null) return
        dirty = false
        throttle.markEncoded(SystemClock.elapsedRealtime())
        try {
            bytes.reset()
            val visible = Bitmap.createBitmap(buf, 0, 0, width.coerceAtMost(buf.width), height.coerceAtMost(buf.height))
            visible.compress(Bitmap.CompressFormat.JPEG, quality, bytes)
            if (visible !== buf) visible.recycle()
            if (ProjectionState.running) ProjectionState.publish(bytes.toByteArray(), width, height)
        } catch (e: Exception) { CarLog.add(this, "Ошибка кодирования кадра: ${e.message}") }
        if (dirty) scheduleEncode()
    }

    /** Если кадров нет (экран статичен), пере-подключаем поверхность: система сразу присылает кадр. */
    private fun watchdog() {
        if (projection == null) return
        if (ProjectionState.frames == 0L && nudges < 3) {
            nudges++
            val d = display; val r = reader
            if (d != null && r != null) { runCatching { d.surface = null; d.surface = r.surface } }
            CarLog.add(this, "Кадров нет — пере-подключаем поверхность (попытка $nudges)")
            capture.postDelayed(::watchdog, 1500)
        } else if (ProjectionState.frames == 0L) {
            ProjectionState.message = "Кадры не приходят. Остановите и запустите трансляцию снова."
        }
    }

    override fun onDestroy() {
        ProjectionState.running = false
        capture.removeCallbacksAndMessages(null)
        projection?.unregisterCallback(callback)
        runCatching { display?.release() }; display = null
        runCatching { reader?.close() }; reader = null
        runCatching { projection?.stop() }; projection = null
        server?.close(); server = null
        buffer?.recycle(); buffer = null
        ProjectionState.address = ""; ProjectionState.addresses = emptyList()
        if (ProjectionState.message == "Трансляция запущена") ProjectionState.message = "Трансляция остановлена"
        ProjectionState.wake()
        ProjectionState.reset()
        thread.quitSafely()
        stopForeground(STOP_FOREGROUND_REMOVE)
        CarLog.add(this, "Захват экрана остановлен")
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "dev.carfiles.STOP"
        const val EXTRA_DATA = "resultData"
        const val EXTRA_CODE = "resultCode"
        const val EXTRA_FPS = "fps"
        const val EXTRA_MAX_SIDE = "maxSide"
        const val EXTRA_QUALITY = "quality"
        private const val CHANNEL = "screen-capture"
        private const val NOTIFICATION_ID = 42
    }
}
