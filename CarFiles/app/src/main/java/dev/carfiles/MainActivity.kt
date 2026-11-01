package dev.carfiles

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/** Главный экран: статус и четыре крупных действия. */
class MainActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var streamChip: TextView
    private lateinit var streamLine: TextView
    private lateinit var autoLine: TextView

    private val tick = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(screen {
            header("CarFiles", "Экран телефона на Android Auto, YouTube и медиатека")
            card {
                addView(label("Состояние", 13f, Theme.muted))
                gap(context, 8)
                streamChip = chip("Трансляция выключена", Theme.muted)
                gap(context, 10)
                streamLine = label("", 14f, Theme.text).also { addView(it) }
                gap(context, 6)
                autoLine = label("", 13f, Theme.muted).also { addView(it) }
            }
            actionCard("📡", "Трансляция экрана", "Запуск, настройки, проверка потока", Theme.accent) { open(ProjectionActivity::class.java) }
            actionCard("▶", "YouTube", "Встроенный браузер, остановка при выходе", Theme.danger) { open(YouTubeActivity::class.java) }
            actionCard("🗂", "Медиатека", "Папки, аудио, видео и фото с телефона", Theme.warn) { open(LibraryActivity::class.java) }
            actionCard("🩺", "Диагностика Android Auto", "Версии, журнал подключений", Theme.ok) { open(DiagnosticsActivity::class.java) }
        })
    }

    private fun open(cls: Class<*>) = startActivity(Intent(this, cls))

    private fun render() {
        if (ProjectionState.running) {
            val f = ProjectionState.latest
            streamChip.text = "● Трансляция идёт"; streamChip.setTextColor(Theme.ok)
            streamLine.text = "${ProjectionState.frames} кадров · ${"%.1f".format(ProjectionState.fps)} к/с" +
                (f?.let { " · ${it.width}×${it.height}" } ?: " · ждём первый кадр")
        } else {
            streamChip.text = "Трансляция выключена"; streamChip.setTextColor(Theme.muted)
            streamLine.text = "Откройте «Трансляцию экрана» и нажмите «Начать»"
        }
        val aa = runCatching { packageManager.getPackageInfo("com.google.android.projection.gearhead", 0).versionName }.getOrNull()
        autoLine.text = if (aa == null) "Android Auto не найден на телефоне" else "Android Auto $aa"
    }

    override fun onStart() { super.onStart(); handler.post(tick) }
    override fun onStop() { handler.removeCallbacks(tick); super.onStop() }
}
