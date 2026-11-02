package dev.carfiles

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал событий, который переживает перезапуск процесса и виден на экране «Диагностика».
 * Нужен, чтобы после поездки было понятно, дошёл ли host Android Auto до нашего сервиса и экрана.
 */
object CarLog {
    private const val PREFS = "car_diagnostics"
    private const val KEY = "log"
    private const val MAX = 80
    private val fmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())

    @JvmStatic @Synchronized fun add(context: Context, text: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lines = (prefs.getString(KEY, "") ?: "").lineSequence().filter { it.isNotBlank() }.toMutableList()
        lines += "${fmt.format(Date())}  $text".replace('\n', ' ')
        prefs.edit().putString(KEY, lines.takeLast(MAX).joinToString("\n")).apply()
    }

    @JvmStatic @Synchronized fun lines(context: Context): List<String> =
        (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: "")
            .lineSequence().filter { it.isNotBlank() }.toList().asReversed()

    @JvmStatic fun mark(context: Context, key: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(key, System.currentTimeMillis()).apply()
    }
    @JvmStatic fun time(context: Context, key: String): Long =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(key, 0L)

    @JvmStatic @Synchronized fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
