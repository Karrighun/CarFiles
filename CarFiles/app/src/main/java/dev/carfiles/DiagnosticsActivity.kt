package dev.carfiles

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.text.DateFormat
import java.util.Date

/** Диагностика: что видит телефон и дошёл ли host Android Auto до нашего сервиса и экрана. */
class DiagnosticsActivity : ComponentActivity() {
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(screen { header("Диагностика", "Android Auto и журнал подключений"); content = this })
    }

    override fun onResume() { super.onResume(); build() }

    private fun build() {
        content.removeAllViews()
        val prefs = getSharedPreferences("car_diagnostics", MODE_PRIVATE)
        val installer = runCatching { packageManager.getInstallSourceInfo(packageName).installingPackageName }.getOrNull() ?: "APK / не указан"
        val aa = runCatching { packageManager.getPackageInfo("com.google.android.projection.gearhead", 0).versionName }.getOrNull()
        fun stamp(v: Long) = if (v == 0L) "ни разу" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(v))
        val created = prefs.getLong("projection_service_created", 0)
        val bound = prefs.getLong("projection_service_bound", 0)
        val screen = prefs.getLong("projection_screen_created", 0)
        val lab = true

        content.card {
            addView(label("Телефон", 13f, Theme.muted))
            row("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            row("Модель", "${Build.MANUFACTURER} ${Build.MODEL}")
            row("Android Auto", aa ?: "не найден", if (aa == null) Theme.danger else Theme.text)
            row("Пакет", packageName)
            row("Источник установки", installer)
            row("Сборка с автоэкраном", if (lab) "да (Lab)" else "нет — только аудио", if (lab) Theme.ok else Theme.warn)
        }
        content.card(if (screen != 0L) Theme.ok else Theme.warn) {
            addView(label("Путь до экрана машины", 13f, Theme.muted))
            row("1. Car App сервис создан", stamp(created), if (created != 0L) Theme.ok else Theme.warn)
            row("2. Сессия Android Auto", stamp(bound), if (bound != 0L) Theme.ok else Theme.warn)
            row("3. Поверхность экрана получена", stamp(screen), if (screen != 0L) Theme.ok else Theme.warn)
            gap(context, 8)
            addView(label(
                if (!lab) "Установлена обычная сборка: в машине доступно только аудио. Для видео нужна сборка CarFiles Lab."
                else if (screen != 0L) "Host Android Auto запускал наш экран. Если картинки нет — смотрите журнал ниже и раздел «Трансляция экрана»."
                else if (bound != 0L) "Сессия открыта, но поверхность не выдана. Откройте CarFiles среди навигационных приложений на экране машины (иконка навигации)."
                else "Host не обращался к сервису. Это значит, что Android Auto не показывает приложение: проверьте режим разработчика и «Неизвестные источники», затем переподключитесь к машине.", 13f, Theme.muted))
        }
        content.card {
            addView(label("Журнал событий", 13f, Theme.muted)); gap(context, 8)
            val lines = CarLog.lines(context)
            if (lines.isEmpty()) addView(label("Пока пусто", 14f, Theme.muted))
            lines.take(40).forEach { addView(label(it, 12f, Theme.text).apply { setPadding(0, context.dp(3), 0, context.dp(3)); setTextIsSelectable(true) }) }
        }
        content.secondaryButton("Скопировать журнал") {
            val text = "CarFiles ${packageName} · Android ${Build.VERSION.RELEASE} · AA ${aa ?: "-"}\n" + CarLog.lines(this).joinToString("\n")
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("CarFiles log", text))
            Toast.makeText(this, "Журнал скопирован", Toast.LENGTH_SHORT).show()
        }
        content.secondaryButton("Очистить журнал") { CarLog.clear(this); build() }
    }
}
