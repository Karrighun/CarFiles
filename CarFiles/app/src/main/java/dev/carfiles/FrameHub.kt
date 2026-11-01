package dev.carfiles

import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Один закодированный кадр экрана. [seq] растёт на единицу с каждым новым кадром. */
class Frame(val seq: Long, val jpeg: ByteArray, val width: Int, val height: Int, val timeMs: Long)

/**
 * Троттлинг с «хвостом»: последний кадр никогда не теряется.
 *
 * Старая версия просто отбрасывала кадры, пришедшие раньше 250 мс после предыдущего. Если после
 * такого кадра экран переставал меняться, на машине навсегда оставалась устаревшая картинка —
 * одна из причин «работает через раз».
 */
class FrameThrottle(private val minIntervalMs: Long) {
    private var lastEncodeAt = Long.MIN_VALUE / 2
    /** Через сколько миллисекунд можно кодировать следующий кадр (0 — прямо сейчас). */
    fun delayFor(now: Long): Long = maxOf(0L, lastEncodeAt + minIntervalMs - now)
    fun markEncoded(now: Long) { lastEncodeAt = now }
}

/**
 * Общее состояние трансляции и «шина» кадров. Не зависит от Android, поэтому проверяется
 * обычными JVM-тестами. Потребители: HTTP-сервер (MJPEG) и экран Android Auto (в одном процессе).
 */
object ProjectionState {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val listeners = CopyOnWriteArraySet<(Frame) -> Unit>()

    @Volatile var running = false
    @Volatile var address = ""
    @Volatile var addresses: List<String> = emptyList()
    @Volatile var message = "Нажмите «Начать трансляцию». Система запросит разрешение на захват экрана."
    @Volatile var latest: Frame? = null
        private set
    @Volatile var frames = 0L
        private set
    @Volatile var fps = 0.0
        private set
    @Volatile var clients = 0
    @Volatile var width = 0
    @Volatile var height = 0
    @Volatile var startedAt = 0L

    private var windowStart = 0L
    private var windowFrames = 0

    val latestJpeg: ByteArray? get() = latest?.jpeg

    fun publish(jpeg: ByteArray, w: Int, h: Int, now: Long = System.currentTimeMillis()) {
        val frame: Frame
        lock.withLock {
            frame = Frame((latest?.seq ?: 0L) + 1, jpeg, w, h, now)
            latest = frame
            frames++
            if (windowStart == 0L) windowStart = now
            windowFrames++
            if (now - windowStart >= 1000) { fps = windowFrames * 1000.0 / (now - windowStart); windowStart = now; windowFrames = 0 }
            changed.signalAll()
        }
        listeners.forEach { runCatching { it(frame) } }
    }

    /** Ждёт кадр новее [afterSeq]; null — если за [timeoutMs] нового нет. */
    fun awaitNewer(afterSeq: Long, timeoutMs: Long): Frame? {
        var nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        lock.lock()
        try {
            while (true) {
                val current = latest
                if (current != null && current.seq > afterSeq) return current
                if (nanos <= 0) return null
                nanos = changed.awaitNanos(nanos)
            }
        } finally { lock.unlock() }
    }

    fun addListener(listener: (Frame) -> Unit) { listeners.add(listener) }
    fun removeListener(listener: (Frame) -> Unit) { listeners.remove(listener) }

    /** Будит ожидающих (например, при остановке), чтобы потоки клиентов закрылись сразу. */
    fun wake() = lock.withLock { changed.signalAll() }

    fun reset() {
        lock.withLock {
            latest = null; frames = 0; fps = 0.0; windowStart = 0; windowFrames = 0
            clients = 0; width = 0; height = 0
            changed.signalAll()
        }
    }
}
