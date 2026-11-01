package dev.carfiles

import android.graphics.ImageDecoder
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.util.concurrent.Executors

@androidx.media3.common.util.UnstableApi
class ViewerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private val io = Executors.newSingleThreadExecutor()
    private var position = 0L
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        position = savedInstanceState?.getLong("position") ?: 0L
    }
    override fun onStart() {
        super.onStart()
        val uri = intent.data ?: run { finish(); return }
        if (intent.getStringExtra("kind") == "image") {
            val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            setContentView(image)
            io.execute {
                val decoded = runCatching {
                    ImageDecoder.decodeDrawable(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                        val scale = minOf(1.0, 2048.0 / maxOf(info.size.width, info.size.height))
                        decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
                    }
                }
                runOnUiThread { if (!isDestroyed) decoded.fold({ image.setImageDrawable(it) }, { showError(it.message) }) }
            }
        } else {
            val view = PlayerView(this)
            setContentView(view)
            player = ExoPlayer.Builder(this).build().also { engine ->
                view.player = engine
                engine.setAudioAttributes(androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                engine.setHandleAudioBecomingNoisy(true)
                engine.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) { showError("Не удалось воспроизвести файл: ${error.errorCodeName}") }
                })
                engine.setMediaItem(MediaItem.fromUri(uri)); engine.seekTo(position); engine.prepare()
                // Start explicitly using player controls; do not autoplay video on lifecycle restart.
            }
        }
    }
    private fun showError(message: String?) { setContentView(TextView(this).apply { text = message ?: "Файл недоступен" }) }
    override fun onSaveInstanceState(outState: Bundle) { outState.putLong("position", player?.currentPosition ?: position); super.onSaveInstanceState(outState) }
    override fun onStop() { player?.let { position = it.currentPosition; it.release() }; player = null; super.onStop() }
    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }
}
