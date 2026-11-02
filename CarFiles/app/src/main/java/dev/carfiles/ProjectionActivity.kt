package dev.carfiles

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.text.DateFormat
import java.util.Date

/** Экран трансляции: запуск, настройки качества, живая проверка потока и состояние автомобильного экрана. */
class ProjectionActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("projection", MODE_PRIVATE) }
    private val carPrefs by lazy { getSharedPreferences("car_diagnostics", MODE_PRIVATE) }

    private lateinit var chip: TextView
    private lateinit var stats: TextView
    private lateinit var message: TextView
    private lateinit var addressView: TextView
    private lateinit var carLine: TextView
    private lateinit var preview: ImageView
    private lateinit var previewCard: LinearLayout
    private lateinit var toggle: TextView
    private var shownSeq = 0L

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { askCapture() }
    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            startForegroundService(Intent(this, ProjectionService::class.java)
                .putExtra(ProjectionService.EXTRA_CODE, result.resultCode)
                .putExtra(ProjectionService.EXTRA_DATA, data)
                .putExtra(ProjectionService.EXTRA_FPS, prefs.getInt("fps", 10))
                .putExtra(ProjectionService.EXTRA_MAX_SIDE, prefs.getInt("side", 960)))
            ProjectionState.message = "Запускаем захват…"
        } else ProjectionState.message = "Захват экрана не разрешён. Нажмите «Начать» и подтвердите запрос системы."
    }

    private val refresh = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 500) }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(screen {
            header("Трансляция экрана", "Картинка телефона → экран автомобиля")
            card {
                chip = chip("", Theme.muted)
                gap(context, 10)
                stats = label("", 15f, Theme.text).also { addView(it) }
                gap(context, 6)
                message = label("", 13f, Theme.muted).also { addView(it) }
            }
            toggle = primaryButton("Начать трансляцию") { onToggle() }

            card {
                addView(label("Качество", 13f, Theme.muted)); gap(context, 10)
                addView(label("Кадров в секунду", 14f)); gap(context, 6)
                segmented("fps", listOf(5, 10, 15, 24), 10) { "$it" }; gap(context, 12)
                addView(label("Размер по длинной стороне", 14f)); gap(context, 6)
                segmented("side", listOf(640, 960, 1280, 1600), 960) { "$it" }
                gap(context, 8)
                addView(label("Изменения применяются при следующем запуске. Для машины достаточно 10 к/с и 960 px.", 12f, Theme.muted))
            }

            card {
                addView(label("Автомобильный экран", 13f, Theme.muted)); gap(context, 8)
                carLine = label("", 14f, Theme.text).also { addView(it) }
            }

            previewCard = card {
                addView(label("Что уходит в поток", 13f, Theme.muted)); gap(context, 10)
                preview = ImageView(context).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; background = context.rounded(Theme.bg, 12) }
                addView(preview, LinearLayout.LayoutParams(-1, context.dp(220)))
            }

            card {
                addView(label("Адрес потока (MJPEG)", 13f, Theme.muted)); gap(context, 8)
                val addr = label("—", 13f, Theme.text); addr.setTextIsSelectable(true); addView(addr); addressView = addr
                gap(context, 12)
                secondaryButton("Скопировать адрес") {
                    if (ProjectionState.running) {
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("CarFiles", ProjectionState.address))
                        Toast.makeText(context, "Адрес скопирован", Toast.LENGTH_SHORT).show()
                    }
                }
                secondaryButton("Проверить поток в браузере") {
                    if (ProjectionState.running) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ProjectionState.address)))
                }
                addView(label("Поток виден только тому, кто знает адрес с временным ключом. Ключ меняется при каждом запуске.", 12f, Theme.muted))
            }
        })
    }

    private fun LinearLayout.segmented(key: String, options: List<Int>, default: Int, name: (Int) -> String) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val views = mutableListOf<Pair<Int, TextView>>()
        fun paint() = views.forEach { (v, t) ->
            val on = v == prefs.getInt(key, default)
            t.setTextColor(if (on) android.graphics.Color.WHITE else Theme.muted)
            t.background = context.rounded(if (on) Theme.accent else Theme.surfaceHi, 12, if (on) null else Theme.stroke)
        }
        options.forEach { v ->
            val t = context.label(name(v), 15f, Theme.muted, bold = true).apply {
                gravity = android.view.Gravity.CENTER; minHeight = context.dp(44)
                setOnClickListener { prefs.edit().putInt(key, v).apply(); paint() }
            }
            views.add(v to t)
            row.addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { if (v != options.last()) marginEnd = context.dp(8) })
        }
        paint(); addView(row)
    }

    private fun onToggle() {
        if (ProjectionState.running) {
            startService(Intent(this, ProjectionService::class.java).setAction(ProjectionService.ACTION_STOP))
        } else if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)   // без уведомления нельзя остановить трансляцию из шторки
        } else askCapture()
    }

    private fun askCapture() {
        if (!ProjectionState.running) capture.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }

    private fun render() {
        val running = ProjectionState.running
        val f = ProjectionState.latest
        chip.text = if (running) "● Идёт трансляция" else "Остановлено"
        chip.setTextColor(if (running) Theme.ok else Theme.muted)
        toggle.text = if (running) "Остановить трансляцию" else "Начать трансляцию"
        toggle.background = rounded(if (running) Theme.danger else Theme.accent, 18)
        stats.text = if (running) "${ProjectionState.frames} кадров · ${"%.1f".format(ProjectionState.fps)} к/с" +
            (f?.let { "\n${it.width}×${it.height} · ${it.jpeg.size / 1024} КБ/кадр" } ?: "\nЖдём первый кадр…") +
            "\nКлиентов по сети: ${ProjectionState.clients}" else "Нет активного потока"
        message.text = ProjectionState.message
        addressView.text = if (running) ProjectionState.addresses.joinToString("\n") else "—"
        previewCard.visibility = if (running && f != null) android.view.View.VISIBLE else android.view.View.GONE
        if (f != null && f.seq != shownSeq) {
            shownSeq = f.seq
            BitmapFactory.decodeByteArray(f.jpeg, 0, f.jpeg.size)?.let { preview.setImageBitmap(it) }
        }
        val created = carPrefs.getLong("projection_screen_created", 0)
        val shown = carPrefs.getLong("car_last_frame", 0)
        fun stamp(v: Long) = DateFormat.getTimeInstance().format(Date(v))
        carLine.text = when {
            created == 0L -> "Экран машины ещё не получал поверхность. В Android Auto откройте CarFiles среди навигационных приложений. Подробности — в «Диагностике»."
            shown == 0L || !running -> "Экран машины создан ${stamp(created)}. Кадры пока не показывались — запустите трансляцию."
            System.currentTimeMillis() - shown < 5000 -> "Машина получает кадры · последний ${stamp(shown)}"
            else -> "Машина не получает кадры с ${stamp(shown)}. Откройте раздел «Экран телефона» на автомобильном экране."
        }
    }

    override fun onStart() { super.onStart(); handler.post(refresh) }
    override fun onStop() { handler.removeCallbacks(refresh); super.onStop() }
}
