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
        val created = prefs.getLong("car_service_created", 0)
        val bound = prefs.getLong("car_session", 0)
        val screen = prefs.getLong("car_surface", 0)
        val lastFrame = prefs.getLong("car_last_frame", 0)
        val trusted = installer == "com.android.vending"
        val category = if (CarCategory.current(this) == CarCategory.NAVIGATION) "Навигация" else "Погода"

        content.card {
            addView(label("Телефон", 13f, Theme.muted))
            row("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            row("Модель", "${Build.MANUFACTURER} ${Build.MODEL}")
            row("Android Auto", aa ?: "не найден", if (aa == null) Theme.danger else Theme.text)
            row("Пакет", packageName)
            row("Источник установки", installer, if (trusted) Theme.ok else Theme.warn)
            row("Категория в Android Auto", category)
        }
        content.card(if (screen != 0L) Theme.ok else Theme.warn) {
            addView(label("Путь до экрана машины", 13f, Theme.muted))
            row("1. Car App сервис создан", stamp(created), if (created != 0L) Theme.ok else Theme.warn)
            row("2. Сессия Android Auto", stamp(bound), if (bound != 0L) Theme.ok else Theme.warn)
            row("3. Поверхность экрана получена", stamp(screen), if (screen != 0L) Theme.ok else Theme.warn)
            row("4. Последний кадр на машине", stamp(lastFrame), if (lastFrame != 0L) Theme.ok else Theme.warn)
            gap(context, 8)
            addView(label(
                if (created == 0L) "Android Auto ещё ни разу не обращался к сервису. Проверьте: режим разработчика → «Неизвестные источники», " +
                    "«Настроить меню» → включить CarFiles Экран, затем переподключитесь к машине. " + (if (!trusted) "Приложение установлено не из Google Play — для приложений-шаблонов Android Auto может скрывать такие установки (см. ниже)." else "")
                else if (bound == 0L) "Сервис создан, но сессия не открыта: откройте CarFiles Экран на экране машины."
                else if (screen == 0L) "Сессия открыта, но поверхность не выдана. Смените категорию (Навигация ↔ Погода) в «Трансляции экрана» и переподключитесь."
                else if (lastFrame == 0L) "Поверхность получена. Запустите трансляцию экрана на телефоне."
                else "Кадры выводятся на экран машины.", 13f, Theme.muted))
        }
        if (!trusted) content.card(Theme.warn) {
            addView(label("Источник установки", 13f, Theme.muted)); gap(context, 6)
            addView(label("Некоторые версии Android Auto показывают приложения-шаблоны только если они установлены «из магазина». " +
                "Если приложение не появляется в списке, переустановите APK с указанием источника (на компьютере с ADB): " +
                "adb install -r -i com.android.vending CarFiles-0.5.0.apk", 13f, Theme.text).apply { setTextIsSelectable(true) })
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
