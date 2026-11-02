package dev.carfiles

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Вывод экрана телефона на Android Auto через Car App Library.
 *
 * Старый путь (aauto SDK, CarActivityService) современный Android Auto не вызывает вообще — в журнале
 * тестировщика не было ни одной записи от него. Здесь используется поддерживаемый механизм: приложение
 * категории NAVIGATION получает от host-а Surface на весь экран машины (NavigationTemplate) и рисует на нём.
 * Для видимости в списке нужен режим разработчика Android Auto → «Неизвестные источники».
 */
class MirrorCarAppService : CarAppService() {
    override fun onCreate() { super.onCreate(); stamp("projection_service_created"); CarLog.add(this, "Car App: сервис создан host-ом Android Auto") }
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    override fun onCreateSession(): Session = MirrorSession()
    private fun stamp(key: String) = getSharedPreferences("car_diagnostics", MODE_PRIVATE).edit().putLong(key, System.currentTimeMillis()).apply()
}

class MirrorSession : Session() {
    private var renderer: MirrorRenderer? = null

    override fun onCreateScreen(intent: Intent): Screen {
        val ctx = carContext
        ctx.getSharedPreferences("car_diagnostics", android.content.Context.MODE_PRIVATE).edit().putLong("projection_service_bound", System.currentTimeMillis()).apply()
        CarLog.add(ctx, "Car App: сессия Android Auto открыта (API хоста ${ctx.carAppApiLevel})")
        val r = MirrorRenderer(ctx).also { renderer = it }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                try { ctx.getCarService(AppManager::class.java).setSurfaceCallback(r) }
                catch (e: Exception) { CarLog.add(ctx, "Car App: не удалось запросить поверхность: ${e.javaClass.simpleName}: ${e.message}") }
            }
            override fun onDestroy(owner: LifecycleOwner) { r.release(); CarLog.add(ctx, "Car App: сессия закрыта") }
        })
        return MirrorScreen(ctx)
    }
}

class MirrorScreen(ctx: CarContext) : Screen(ctx) {
    override fun onGetTemplate(): Template {
        val strip = ActionStrip.Builder()
            .addAction(Action.Builder().setTitle("Остановить").setOnClickListener {
                carContext.startService(Intent(carContext, ProjectionService::class.java).setAction(ProjectionService.ACTION_STOP))
            }.build())
            .build()
        return NavigationTemplate.Builder().setActionStrip(strip).build()
    }
}

/** Рисует последний кадр трансляции на поверхности машины (по размеру экрана, с полями). */
class MirrorRenderer(private val ctx: CarContext) : SurfaceCallback {
    @Volatile private var surface: Surface? = null
    @Volatile private var running = false
    private var thread: Thread? = null
    private var width = 0
    private var height = 0
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(142, 160, 188); textAlign = Paint.Align.CENTER }

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        surface = container.surface; width = container.width; height = container.height
        ctx.getSharedPreferences("car_diagnostics", android.content.Context.MODE_PRIVATE).edit().putLong("projection_screen_created", System.currentTimeMillis()).apply()
        CarLog.add(ctx, "Car App: поверхность получена ${width}×${height}, dpi ${container.dpi}")
        start()
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) { CarLog.add(ctx, "Car App: поверхность потеряна"); stop(); surface = null }

    private fun start() {
        stop()
        running = true
        thread = Thread({ loop() }, "CarFiles-car-render").apply { isDaemon = true; start() }
    }
    private fun stop() { running = false; thread?.interrupt(); thread?.join(500); thread = null }
    fun release() { stop(); surface = null }

    private fun loop() {
        var seq = 0L
        var bitmap: Bitmap? = null
        var drawn = 0L
        var lastStamp = 0L
        while (running) {
            val frame = ProjectionState.awaitNewer(seq, 250)
            if (frame != null) {
                seq = frame.seq
                BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size)?.let { bitmap = it }
            } else if (bitmap != null && ProjectionState.running) {
                continue                       // кадров нет, но картинка актуальна — не перерисовываем зря
            }
            val s = surface ?: break
            if (!s.isValid) break
            var canvas: Canvas? = null
            try {
                canvas = s.lockCanvas(null)
                canvas.drawColor(Color.rgb(11, 18, 32))
                val b = bitmap
                if (b != null && ProjectionState.running) {
                    val scale = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
                    val w = b.width * scale; val h = b.height * scale
                    val l = (width - w) / 2f; val t = (height - h) / 2f
                    canvas.drawBitmap(b, null, RectF(l, t, l + w, t + h), paint)
                    drawn++
                    val now = System.currentTimeMillis()
                    if (drawn == 1L) CarLog.add(ctx, "Car App: первый кадр выведен на экран машины")
                    if (now - lastStamp > 1000) {
                        lastStamp = now
                        ctx.getSharedPreferences("car_diagnostics", android.content.Context.MODE_PRIVATE).edit().putLong("car_last_frame", now).apply()
                    }
                } else {
                    text.textSize = height / 18f
                    canvas.drawText("CarFiles", width / 2f, height / 2f - text.textSize, text)
                    text.textSize = height / 26f
                    canvas.drawText("Запустите трансляцию экрана на телефоне", width / 2f, height / 2f + text.textSize, text)
                }
            } catch (e: Exception) {
                CarLog.add(ctx, "Car App: ошибка рисования: ${e.javaClass.simpleName}: ${e.message}"); break
            } finally {
                if (canvas != null) runCatching { s.unlockCanvasAndPost(canvas) }
            }
        }
    }
}
