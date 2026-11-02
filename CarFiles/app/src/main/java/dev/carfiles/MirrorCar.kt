package dev.carfiles

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Presentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.widget.ImageView
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
 * Старый путь (aauto SDK, CarActivityService) современный Android Auto не вызывает вообще: в журнале
 * тестировщика на AA 17.9 не было ни одной записи от него, а в машине открывался только аудио-браузер.
 * Здесь используется поддерживаемый механизм: приложение получает от host-а Surface на весь экран машины
 * (NavigationTemplate) и рисует на нём кадры из [ProjectionState].
 *
 * Два сервиса-двойника с разной категорией: включён всегда ровно один (см. [CarCategory]).
 *  • NAVIGATION — по документации Google единственная категория с доступом к поверхности;
 *  • WEATHER — чтобы не занимать единственный слот навигации (Яндекс/Google Карты остаются на месте).
 */
abstract class BaseMirrorService : CarAppService() {
    override fun onCreate() {
        super.onCreate()
        CarLog.mark(this, "car_service_created")
        CarLog.add(this, "Car App: сервис создан host-ом Android Auto (${javaClass.simpleName})")
    }
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    override fun onCreateSession(): Session = MirrorSession()
}
class MirrorCarAppService : BaseMirrorService()
class MirrorWeatherCarAppService : BaseMirrorService()

/** Какая категория сейчас объявлена host-у. Меняется переключением компонента, нужен переподключение к машине. */
object CarCategory {
    const val NAVIGATION = "navigation"
    const val WEATHER = "weather"
    fun current(context: Context): String {
        val pm = context.packageManager
        val nav = ComponentName(context, MirrorCarAppService::class.java)
        return if (pm.getComponentEnabledSetting(nav) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) WEATHER else NAVIGATION
    }
    fun set(context: Context, category: String) {
        val pm = context.packageManager
        fun toggle(cls: Class<*>, on: Boolean) = pm.setComponentEnabledSetting(ComponentName(context, cls),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        toggle(MirrorCarAppService::class.java, category == NAVIGATION)
        toggle(MirrorWeatherCarAppService::class.java, category == WEATHER)
        CarLog.add(context, "Категория в Android Auto: $category")
    }
}

/** Уведомление «Подключено к машине — запустить трансляцию»: один тап вместо поиска приложения. */
object CarConnectNotice {
    private const val ID = 43
    private const val CHANNEL = "car-connect"
    fun show(context: Context) {
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Подключение к машине", NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(context, 2,
                Intent(context, ProjectionActivity::class.java).putExtra(ProjectionActivity.EXTRA_AUTOSTART, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(ID, android.app.Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("CarFiles: машина подключена")
                .setContentText("Нажмите, чтобы начать трансляцию экрана на машину")
                .setContentIntent(open).setAutoCancel(true).build())
        }
    }
    fun cancel(context: Context) { runCatching { context.getSystemService(NotificationManager::class.java).cancel(ID) } }
}

class MirrorSession : Session() {
    private var renderer: MirrorRenderer? = null

    override fun onCreateScreen(intent: Intent): Screen {
        val ctx = carContext
        CarLog.mark(ctx, "car_session")
        CarLog.add(ctx, "Car App: сессия Android Auto открыта (API хоста ${ctx.carAppApiLevel})")
        if (!ProjectionState.running) CarConnectNotice.show(ctx)
        val r = MirrorRenderer(ctx).also { renderer = it }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                try { ctx.getCarService(AppManager::class.java).setSurfaceCallback(r); CarLog.add(ctx, "Car App: поверхность запрошена") }
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

/**
 * Рисует последний кадр трансляции на поверхности машины с учётом видимой области (интерфейс Android Auto
 * перекрывает края). Основной путь — Canvas; если host не даёт заблокировать поверхность, автоматически
 * включается запасной путь: VirtualDisplay + Presentation с ImageView.
 */
class MirrorRenderer(private val ctx: CarContext) : SurfaceCallback {
    @Volatile private var surface: Surface? = null
    @Volatile private var running = false
    @Volatile private var visible: Rect? = null
    private var thread: Thread? = null
    private var width = 0
    private var height = 0
    private var dpi = 160
    private val main = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(142, 160, 188); textAlign = Paint.Align.CENTER }
    private val bg = Color.rgb(11, 18, 32)
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var image: ImageView? = null
    @Volatile private var usePresentation = false

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        surface = container.surface; width = container.width; height = container.height; dpi = container.dpi
        CarLog.mark(ctx, "car_surface")
        CarLog.add(ctx, "Car App: поверхность получена ${width}×${height}, dpi $dpi")
        start()
    }
    override fun onVisibleAreaChanged(visibleArea: Rect) { visible = Rect(visibleArea); CarLog.add(ctx, "Car App: видимая область $visibleArea") }
    override fun onSurfaceDestroyed(container: SurfaceContainer) { CarLog.add(ctx, "Car App: поверхность потеряна"); stop(); surface = null }

    private fun start() { stop(); running = true; thread = Thread({ loop() }, "CarFiles-car-render").apply { isDaemon = true; start() } }
    private fun stop() { running = false; thread?.interrupt(); thread?.join(500); thread = null; closePresentation() }
    fun release() { stop(); surface = null }

    private fun closePresentation() {
        main.post { runCatching { presentation?.dismiss() }; presentation = null; runCatching { display?.release() }; display = null; image = null }
    }

    private fun startPresentation(s: Surface) {
        main.post {
            try {
                val dm = ctx.getSystemService(DisplayManager::class.java)
                val vd = dm.createVirtualDisplay("CarFiles-car", width, height, dpi, s,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION)
                display = vd
                val p = Presentation(ctx, vd.display)
                val v = ImageView(p.context).apply { setBackgroundColor(bg); scaleType = ImageView.ScaleType.FIT_CENTER }
                p.setContentView(v); p.show()
                presentation = p; image = v
                CarLog.add(ctx, "Car App: включён запасной вывод (VirtualDisplay + Presentation)")
            } catch (e: Exception) { CarLog.add(ctx, "Car App: запасной вывод не удался: ${e.javaClass.simpleName}: ${e.message}") }
        }
    }

    private fun area(): RectF {
        val v = visible
        return if (v != null && v.width() > 0 && v.height() > 0) RectF(v) else RectF(0f, 0f, width.toFloat(), height.toFloat())
    }

    private fun loop() {
        var seq = 0L
        var bitmap: Bitmap? = null
        var drawn = 0L
        var lastStamp = 0L
        var failures = 0
        while (running) {
            val frame = ProjectionState.awaitNewer(seq, 250)
            if (frame != null) {
                seq = frame.seq
                BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size)?.let { bitmap = it }
            } else if (bitmap != null && ProjectionState.running && !usePresentation) {
                continue                       // кадров нет, картинка актуальна — не перерисовываем зря
            }
            val s = surface ?: break
            if (!s.isValid) break
            val b = bitmap?.takeIf { ProjectionState.running }
            if (usePresentation) {
                if (display == null && presentation == null) { startPresentation(s); Thread.sleep(300); continue }
                main.post { image?.setImageBitmap(b) }
                if (b != null) {
                    drawn++
                    if (drawn == 1L) CarLog.add(ctx, "Car App: первый кадр выведен (запасной путь)")
                    val now = System.currentTimeMillis()
                    if (now - lastStamp > 1000) { lastStamp = now; CarLog.mark(ctx, "car_last_frame") }
                }
                continue
            }
            var canvas: Canvas? = null
            try {
                canvas = s.lockCanvas(null)
                canvas.drawColor(bg)
                val a = area()
                if (b != null) {
                    val scale = minOf(a.width() / b.width, a.height() / b.height)
                    val w = b.width * scale; val h = b.height * scale
                    val l = a.left + (a.width() - w) / 2f; val t = a.top + (a.height() - h) / 2f
                    canvas.drawBitmap(b, null, RectF(l, t, l + w, t + h), paint)
                    drawn++
                    if (drawn == 1L) CarLog.add(ctx, "Car App: первый кадр выведен на экран машины")
                    val now = System.currentTimeMillis()
                    if (now - lastStamp > 1000) { lastStamp = now; CarLog.mark(ctx, "car_last_frame") }
                } else {
                    text.textSize = a.height() / 14f
                    canvas.drawText("CarFiles", a.centerX(), a.centerY() - text.textSize, text)
                    text.textSize = a.height() / 22f
                    canvas.drawText("Запустите трансляцию экрана на телефоне", a.centerX(), a.centerY() + text.textSize, text)
                }
                failures = 0
            } catch (e: Exception) {
                failures++
                CarLog.add(ctx, "Car App: ошибка рисования (Canvas): ${e.javaClass.simpleName}: ${e.message}")
                if (failures >= 2) { usePresentation = true; canvas = null }
                else Thread.sleep(200)
            } finally {
                if (canvas != null) runCatching { s.unlockCanvasAndPost(canvas) }
            }
        }
    }
}
