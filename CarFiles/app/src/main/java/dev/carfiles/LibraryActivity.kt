package dev.carfiles

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.util.concurrent.Executors

/** Файловый браузер (раньше был на главном экране). Логика та же, оформление новое. */
@androidx.media3.common.util.UnstableApi
class LibraryActivity : ComponentActivity() {
    private lateinit var repo: FileRepository
    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private val io = Executors.newSingleThreadExecutor()
    private val stack = ArrayDeque<Uri>()
    private var generation = 0
    private var controller: MediaController? = null
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                repo.saveRoot(uri)
                stack.clear(); stack.add(uri); load(uri)
            } catch (e: Exception) { status.text = "Не удалось сохранить доступ: ${e.message}" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = FileRepository(this)
        setContentView(screen {
            header("Медиатека", "Файлы не копируются и не удаляются")
            primaryButton("Выбрать папку") { picker.launch(null) }
            secondaryButton("Вверх") { if (stack.size > 1) { stack.removeLast(); load(stack.last()) } }
            secondaryButton("Пауза аудио") { controller?.pause() }
            status = label("", 14f, Theme.muted).also { addView(it) }
            gap(context, 12)
            rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(rows)
        })
        val future = MediaController.Builder(this, SessionToken(this, ComponentName(this, AudioService::class.java))).buildAsync()
        future.addListener({
            try { val connected = future.get(); if (isDestroyed) connected.release() else controller = connected }
            catch (e: Exception) { status.text = "Аудиосервис недоступен: ${e.message}" }
        }, mainExecutor)
        repo.root()?.let { stack.add(it); load(it) } ?: run { status.text = "Выберите папку с файлами на телефоне. В Android Auto доступно аудио." }
    }

    private fun load(uri: Uri) {
        val request = ++generation
        status.text = "Загрузка…"; rows.removeAllViews()
        io.execute {
            val result = runCatching { repo.children(uri) }
            runOnUiThread {
                if (isDestroyed || request != generation) return@runOnUiThread
                result.fold(onSuccess = { files ->
                    status.text = "Файлов и папок: ${files.size}"
                    files.forEach { entry ->
                        val glyph = if (entry.directory) "📁" else when (entry.kind) { "audio" -> "🎵"; "video" -> "🎬"; "image" -> "🖼"; else -> "📄" }
                        rows.actionCard(glyph, entry.name, if (entry.directory) "Папка" else entry.kind, Theme.accent) { onEntry(entry) }
                    }
                }, onFailure = { status.text = it.message ?: "Ошибка чтения" })
            }
        }
    }

    private fun onEntry(entry: FileEntry) {
        if (entry.directory) { stack.add(entry.uri); load(entry.uri) }
        else if (entry.kind == "audio") {
            val active = controller
            if (active == null) status.text = "Аудиосервис ещё подключается"
            else { active.setMediaItem(MediaItem.Builder().setMediaId(entry.uri.toString()).build()); active.prepare(); active.play() }
        } else if (entry.kind in listOf("video", "image")) {
            controller?.pause()
            startActivity(Intent(this, ViewerActivity::class.java).setData(entry.uri).putExtra("kind", entry.kind))
        } else status.text = "Просмотр этого типа файла пока не реализован"
    }

    override fun onDestroy() { controller?.release(); io.shutdownNow(); super.onDestroy() }
}
