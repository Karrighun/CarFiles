package dev.carfiles

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executors

@androidx.media3.common.util.UnstableApi
class AudioService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    private lateinit var library: MediaLibrarySession
    private lateinit var repo: FileRepository
    private val io = Executors.newSingleThreadExecutor()
    private fun item(entry: FileEntry) = MediaItem.Builder()
        .setMediaId(entry.uri.toString()).setUri(entry.uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(entry.name)
            .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()).build()

    private fun rootItem() = MediaItem.Builder().setMediaId("root").setMediaMetadata(MediaMetadata.Builder()
        .setTitle("CarFiles — аудио").setIsBrowsable(true).setIsPlayable(false)
        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()).build()

    override fun onCreate() {
        super.onCreate()
        repo = FileRepository(this)
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
        }
        library = MediaLibrarySession.Builder(this, player, object : MediaLibrarySession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                // Media browsers must bind externally; constrain access at session connection time.
                return if (controller.uid == android.os.Process.myUid() || controller.isTrusted ||
                    controller.packageName == "com.google.android.projection.gearhead") super.onConnect(session, controller)
                else MediaSession.ConnectionResult.reject()
            }
            override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
                params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> {
                return Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))
            }

            override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
                parentId: String, page: Int, pageSize: Int,
                params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                return Futures.submit<LibraryResult<ImmutableList<MediaItem>>>({
                    if (parentId != "root" || page < 0 || pageSize < 1)
                        LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                    else try {
                        val tracks = repo.audio()
                        val start = (page.toLong() * pageSize).coerceAtMost(tracks.size.toLong()).toInt()
                        val end = (start.toLong() + pageSize).coerceAtMost(tracks.size.toLong()).toInt()
                        LibraryResult.ofItemList(tracks.subList(start, end).map(::item), params)
                    } catch (_: Exception) { LibraryResult.ofError(SessionError.ERROR_IO) }
                }, io)
            }
            override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
                mediaId: String): ListenableFuture<LibraryResult<MediaItem>> = Futures.submit<LibraryResult<MediaItem>>({
                try {
                    if (mediaId == "root") return@submit LibraryResult.ofItem(rootItem(), null)
                    val entry = repo.audio().firstOrNull { it.uri.toString() == mediaId }
                    if (entry == null) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                    else LibraryResult.ofItem(item(entry), null)
                } catch (_: Exception) { LibraryResult.ofError(SessionError.ERROR_IO) }
            }, io)

            override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
                Futures.submit<MutableList<MediaItem>>({
                    // Never trust a URI supplied by an exported service's client.
                    val allowed = repo.audio().associateBy { it.uri.toString() }
                    mediaItems.map { requested ->
                        val entry = if (requested.mediaId.isNotEmpty()) allowed[requested.mediaId]
                            else allowed.values.firstOrNull { AudioSearch.matches(it.name, requested.requestMetadata.searchQuery.orEmpty()) }
                        item(requireNotNull(entry) { "Подходящее аудио не найдено в выбранной папке" })
                    }.toMutableList()
                }, io)
        }).build()
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = library
    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) stopSelf()
    }
    override fun onDestroy() { library.release(); player.release(); io.shutdownNow(); super.onDestroy() }
}
