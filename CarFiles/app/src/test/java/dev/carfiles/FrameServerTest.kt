package dev.carfiles

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class FrameServerTest {
    private lateinit var server: FrameServer
    private fun jpeg(n: Int) = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), n.toByte(), 0xFF.toByte(), 0xD9.toByte())
    private fun url(path: String) = "http://127.0.0.1:${server.port}/${server.token}/$path"

    @Before fun setUp() { ProjectionState.reset(); ProjectionState.running = true; server = FrameServer(0) }
    @After fun tearDown() { server.close(); ProjectionState.running = false; ProjectionState.reset() }

    @Test fun throttleKeepsTrailingFrame() {
        val t = FrameThrottle(250)
        assertEquals(0, t.delayFor(1000)); t.markEncoded(1000)
        assertEquals(150, t.delayFor(1100))      // кадр пришёл рано — его не выбрасываем, а откладываем
        assertEquals(0, t.delayFor(1250))
    }

    @Test fun frameJpgNotReadyThenReady() {
        val c1 = URL(url("frame.jpg")).openConnection() as HttpURLConnection
        assertEquals(503, c1.responseCode)
        ProjectionState.publish(jpeg(1), 10, 10)
        val c2 = URL(url("frame.jpg")).openConnection() as HttpURLConnection
        assertEquals(200, c2.responseCode)
        assertArrayEquals(jpeg(1), c2.inputStream.readBytes())
    }

    @Test fun wrongTokenIs404() {
        val c = URL("http://127.0.0.1:${server.port}/deadbeef/frame.jpg").openConnection() as HttpURLConnection
        assertEquals(404, c.responseCode)
    }

    @Test fun mjpegStreamDeliversEveryNewFrameAndReplaysLast() {
        ProjectionState.publish(jpeg(1), 10, 10)
        Socket("127.0.0.1", server.port).use { s ->
            s.soTimeout = 5000
            s.getOutputStream().write("GET /${server.token}/stream.mjpg HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            val input = s.getInputStream()
            assertEquals(1, nextPart(input)[2].toInt())            // последний кадр доставляется сразу при подключении
            ProjectionState.publish(jpeg(2), 10, 10)
            assertEquals(2, nextPart(input)[2].toInt())
            ProjectionState.publish(jpeg(3), 10, 10)
            assertEquals(3, nextPart(input)[2].toInt())
            // экран не меняется → heartbeat повторяет последний кадр
            assertEquals(3, nextPart(input)[2].toInt())
        }
    }

    @Test fun reconnectAfterClientDropsAndManyClients() {
        ProjectionState.publish(jpeg(7), 10, 10)
        repeat(20) {                                            // быстрые подключения/обрывы не должны исчерпывать пул
            Socket("127.0.0.1", server.port).use { s ->
                s.getOutputStream().write("GET /${server.token}/stream.mjpg HTTP/1.1\r\n\r\n".toByteArray())
                assertEquals(7, nextPart(s.getInputStream())[2].toInt())
            }
        }
        val c = URL(url("status.json")).openConnection() as HttpURLConnection
        assertEquals(200, c.responseCode)
        assertTrue(c.inputStream.readBytes().decodeToString().contains("\"running\":true"))
    }

    @Test fun portInUseFallsBackInsteadOfFailing() {
        val second = FrameServer(server.port)
        try { assertNotEquals(server.port, second.port); assertTrue(second.port > 0) } finally { second.close() }
    }

    @Test fun listenersAndSequence() {
        val got = mutableListOf<Long>(); val latch = CountDownLatch(3)
        val l: (Frame) -> Unit = { got += it.seq; latch.countDown() }
        ProjectionState.addListener(l)
        repeat(3) { ProjectionState.publish(jpeg(it), 4, 4) }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(1L, 2L, 3L), got)
        ProjectionState.removeListener(l)
    }

    @Test fun awaitNewerWakesOnPublish() {
        var result: Frame? = null
        val t = thread { result = ProjectionState.awaitNewer(0, 3000) }
        Thread.sleep(100); ProjectionState.publish(jpeg(9), 2, 2); t.join(2000)
        assertEquals(1L, result?.seq)
    }

    /** Читает одну часть multipart и возвращает JPEG-байты. */
    private fun nextPart(input: InputStream): ByteArray {
        var len = -1
        while (true) {
            val line = readLine(input)
            if (line.startsWith("Content-Length:")) len = line.substringAfter(':').trim().toInt()
            if (line.isEmpty() && len >= 0) break
        }
        val body = ByteArray(len); var off = 0
        while (off < len) { val n = input.read(body, off, len - off); if (n < 0) fail("EOF"); off += n }
        return body
    }
    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (true) { val b = input.read(); if (b < 0) fail("EOF"); if (b == 10) return sb.toString().trimEnd('\r'); sb.append(b.toChar()) }
    }
}
