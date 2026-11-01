package dev.carfiles

import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Локальный HTTP-сервер кадров.
 *
 *  /{token}/            — страница-просмотрщик (автопереподключение)
 *  /{token}/stream.mjpg — непрерывный MJPEG (multipart/x-mixed-replace), один TCP-канал на всё время
 *  /{token}/frame.jpg   — последний кадр одним ответом
 *  /{token}/status.json — состояние (для проверки и диагностики)
 *
 * Что исправлено относительно 0.3.2:
 *  • порт 8787 занят после быстрого перезапуска → теперь SO_REUSEADDR и перебор 8787..8797;
 *  • пул из двух потоков с очередью 8 забивался подвисшими клиентами → кэшируемый пул с лимитом клиентов;
 *  • опрос frame.jpg с новым TCP-соединением на каждый кадр → один долгоживущий MJPEG-поток с heartbeat;
 *  • после остановки сервер ждал клиентов → потоки будятся и закрываются сразу.
 */
class FrameServer(preferredPort: Int = 8787, private val maxClients: Int = 6) {
    val token: String = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    @Volatile private var closed = false
    private val active = AtomicInteger(0)
    private val streams = LinkedHashSet<Socket>()
    private val server: ServerSocket = bind(preferredPort)
    val port: Int get() = server.localPort
    private val workers: ExecutorService = Executors.newCachedThreadPool(object : ThreadFactory {
        private val n = AtomicInteger()
        override fun newThread(r: Runnable) = Thread(r, "CarFiles-http-${n.incrementAndGet()}").apply { isDaemon = true }
    })
    private val acceptor = Thread({
        while (!closed) {
            try {
                val socket = server.accept()
                // Новое подключение важнее старого: машина могла переподключиться, а прежний сокет ещё «висит».
                if (active.get() >= maxClients) synchronized(streams) { streams.firstOrNull()?.let { runCatching { it.close() } } }
                active.incrementAndGet()
                try {
                    workers.execute { try { runCatching { serve(socket) } } finally { runCatching { socket.close() }; active.decrementAndGet(); ProjectionState.clients = active.get() } }
                } catch (_: Exception) { runCatching { socket.close() }; active.decrementAndGet() }
                ProjectionState.clients = active.get()
            } catch (_: IOException) { if (closed) break }
        }
    }, "CarFiles-accept").apply { isDaemon = true; start() }

    private fun bind(preferred: Int): ServerSocket {
        var last: IOException? = null
        for (port in (preferred..preferred + 10) + 0) {
            val s = ServerSocket()
            try { s.reuseAddress = true; s.bind(InetSocketAddress(port)); return s } catch (e: IOException) { last = e; runCatching { s.close() } }
        }
        throw last ?: IOException("bind failed")
    }

    /** Все IPv4-адреса телефона (Wi-Fi, точка доступа, USB-модем), лучший — первым. */
    fun urls(): List<String> = candidateHosts().map { "http://$it:$port/$token/" }
    fun address(): String = urls().first()

    private fun serve(socket: Socket) {
        socket.soTimeout = 5000
        socket.tcpNoDelay = true
        val input = BufferedInputStream(socket.getInputStream())
        val line = readLine(input) ?: return
        while (true) { val h = readLine(input) ?: break; if (h.isEmpty()) break }   // заголовки читаем и игнорируем
        val parts = line.split(' ')
        if (parts.size < 2 || (parts[0] != "GET" && parts[0] != "HEAD")) return
        val path = parts[1].substringBefore('?')
        val out = socket.getOutputStream()
        when (path) {
            "/$token/frame.jpg" -> {
                val f = ProjectionState.latest
                if (f == null) respond(out, "503 Service Unavailable", "text/plain", "No frame yet".toByteArray(), retry = true)
                else respond(out, "200 OK", "image/jpeg", f.jpeg)
            }
            "/$token/stream.mjpg" -> {
                synchronized(streams) { streams.add(socket) }
                try { stream(socket, out) } finally { synchronized(streams) { streams.remove(socket) } }
            }
            "/$token/status.json" -> respond(out, "200 OK", "application/json", status().toByteArray())
            "/$token/" -> respond(out, "200 OK", "text/html; charset=utf-8", VIEWER.toByteArray())
            else -> respond(out, "404 Not Found", "text/plain", "Not found".toByteArray())
        }
    }

    /** true, если клиент закрыл соединение (EOF) или сокет закрыт. */
    private fun clientGone(socket: Socket): Boolean {
        if (socket.isClosed) return true
        return try { socket.soTimeout = 1; socket.getInputStream().read() < 0 }
        catch (_: java.net.SocketTimeoutException) { false } catch (_: IOException) { true }
    }

    private fun stream(socket: Socket, out: OutputStream) {
        val boundary = "carfiles"
        out.write("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=$boundary\r\nCache-Control: no-store\r\nPragma: no-cache\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\n\r\n".toByteArray())
        out.flush()
        var seq = 0L
        var lastWrite = 0L
        while (!closed) {
            if (clientGone(socket)) return
            // Ждём новый кадр не дольше 1 с. Если кадров нет (экран не меняется), каждые 2 с повторяем
            // последний, чтобы клиент и сеть не считали канал мёртвым.
            val frame = ProjectionState.awaitNewer(seq, 400)
                ?: ProjectionState.latest?.takeIf { System.currentTimeMillis() - lastWrite >= 2000 }
                ?: continue
            seq = frame.seq
            out.write("--$boundary\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.jpeg.size}\r\nX-Frame: ${frame.seq}\r\n\r\n".toByteArray())
            out.write(frame.jpeg)
            out.write("\r\n".toByteArray())
            out.flush()
            lastWrite = System.currentTimeMillis()
        }
    }

    private fun status(): String {
        val f = ProjectionState.latest
        return """{"running":${ProjectionState.running},"frames":${ProjectionState.frames},"fps":${"%.1f".format(java.util.Locale.US, ProjectionState.fps)},"width":${f?.width ?: 0},"height":${f?.height ?: 0},"clients":${active.get()},"ageMs":${if (f == null) -1 else System.currentTimeMillis() - f.timeMs}}"""
    }

    private fun respond(out: OutputStream, status: String, type: String, body: ByteArray, retry: Boolean = false) {
        out.write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\n${if (retry) "Retry-After: 1\r\n" else ""}Connection: close\r\nX-Content-Type-Options: nosniff\r\n\r\n".toByteArray())
        out.write(body); out.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (sb.length <= 4096) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == 10) return sb.toString()
            if (b != 13) sb.append(b.toChar())
        }
        return null
    }

    fun close() {
        closed = true
        runCatching { server.close() }
        workers.shutdownNow()
        ProjectionState.wake()
    }

    companion object {
        private const val VIEWER = """<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>CarFiles · экран</title><style>html,body{margin:0;height:100%;background:#0b1220;color:#9fb1c9;font:14px system-ui,sans-serif}body{display:flex;flex-direction:column;align-items:center;justify-content:center}img{max-width:100vw;max-height:94vh;object-fit:contain}p{margin:6px}</style><p id="s">Подключение…</p><img id="f" alt=""><script>const f=document.getElementById('f'),s=document.getElementById('s');function go(){f.src='stream.mjpg?t='+Date.now()}f.onload=()=>{s.textContent='CarFiles · экран телефона'};f.onerror=()=>{s.textContent='Переподключение…';setTimeout(go,1000)};go();</script>"""

        /** Адреса IPv4, лучшие первыми: Wi-Fi/точка доступа/USB-модем, затем остальные. */
        fun candidateHosts(): List<String> {
            val result = mutableListOf<Pair<Int, String>>()
            runCatching {
                for (nif in NetworkInterface.getNetworkInterfaces().toList()) {
                    if (!nif.isUp || nif.isLoopback) continue
                    val rank = when {
                        nif.name.startsWith("wlan") || nif.name.startsWith("ap") || nif.name.startsWith("swlan") -> 0
                        nif.name.startsWith("rndis") || nif.name.startsWith("ncm") || nif.name.startsWith("usb") -> 1
                        else -> 2
                    }
                    for (a in nif.inetAddresses) if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) result += rank to a.hostAddress!!
                }
            }
            return result.sortedBy { it.first }.map { it.second }.distinct().ifEmpty { listOf("127.0.0.1") }
        }
    }
}
