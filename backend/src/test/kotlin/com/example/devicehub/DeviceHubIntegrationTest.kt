package com.example.devicehub

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Runs the real server and talks to it like a device and a browser would. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeviceHubIntegrationTest {

    @Value("\${local.server.port}")
    private var port: Int = 0

    private val http = HttpClient.newHttpClient()
    private val json = jacksonObjectMapper()

    @Test
    fun `device registers, reports, receives a command and acknowledges it`() {
        val device = TestDevice.connect(http, URI("ws://localhost:$port/ws/device"))
        device.send("""{"type":"register","name":"it-device"}""")
        device.send("""{"type":"telemetry","status":"active","value":42.5,"timestamp":"2026-10-28T10:00:00Z"}""")

        eventually {
            val state = device("it-device")
            assertEquals(true, state["online"].asBoolean())
            assertEquals(42.5, state["lastValue"].asDouble())
        }

        val response = post("/api/devices/it-device/commands/set-interval", """{"intervalMillis":2000}""")
        assertEquals(202, response.statusCode())
        val commandId = json.readTree(response.body())["commandId"].asString()

        val command = json.readTree(device.nextMessage())
        assertEquals("set_interval", command["type"].asString())
        assertEquals(commandId, command["command_id"].asString())
        assertEquals(2000, command["new_interval_millis"].asLong())

        device.send("""{"type":"ack","command_id":"$commandId"}""")
        eventually {
            val state = device("it-device")
            assertEquals("ACKNOWLEDGED", state["lastCommand"]["status"].asString())
            assertEquals(2000, state["intervalMillis"].asLong())
        }

        device.close()
        eventually { assertEquals(false, device("it-device")["online"].asBoolean()) }
    }

    @Test
    fun `telemetry before register closes the connection with policy violation`() {
        val device = TestDevice.connect(http, URI("ws://localhost:$port/ws/device"))

        device.send("""{"type":"telemetry","status":"active","value":1.0,"timestamp":"2026-10-28T10:00:00Z"}""")

        assertEquals(1008, device.closeCode.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `invalid message is ignored and the connection stays open`() {
        val device = TestDevice.connect(http, URI("ws://localhost:$port/ws/device"))

        device.send("not json")
        device.send("""{"type":"register","name":"it-tolerant"}""")

        eventually { assertEquals(true, device("it-tolerant")["online"].asBoolean()) }
        device.close()
    }

    @Test
    fun `commands to unknown or offline devices are rejected`() {
        assertEquals(404, post("/api/devices/nobody/commands/set-interval", """{"intervalMillis":2000}""").statusCode())

        val device = TestDevice.connect(http, URI("ws://localhost:$port/ws/device"))
        device.send("""{"type":"register","name":"it-offline"}""")
        eventually { assertNotNull(deviceOrNull("it-offline")) }
        assertEquals(400, post("/api/devices/it-offline/commands/set-interval", """{"intervalMillis":0}""").statusCode())

        device.close()
        eventually { assertEquals(false, device("it-offline")["online"].asBoolean()) }
        assertEquals(409, post("/api/devices/it-offline/commands/set-interval", """{"intervalMillis":2000}""").statusCode())
    }

    @Test
    fun `event stream starts with a snapshot`() {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port/api/devices/stream")).build()
        val lines = http.send(request, HttpResponse.BodyHandlers.ofLines()).body()

        val firstEvent = lines.iterator().next()
        lines.close()

        assertEquals("event:snapshot", firstEvent)
    }

    private fun deviceOrNull(name: String): JsonNode? {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port/api/devices")).build()
        val body = http.send(request, HttpResponse.BodyHandlers.ofString()).body()
        return json.readTree(body).firstOrNull { it["name"].asString() == name }
    }

    private fun device(name: String): JsonNode = assertNotNull(deviceOrNull(name), "device $name not found")

    private fun post(path: String, body: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun eventually(timeout: Duration = Duration.ofSeconds(5), assertion: () -> Unit) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (true) {
            try {
                return assertion()
            } catch (e: AssertionError) {
                if (System.nanoTime() > deadline) throw e
                Thread.sleep(50)
            }
        }
    }

    /** Minimal device built on the JDK WebSocket client, independent of the backend's types. */
    private class TestDevice : WebSocket.Listener {
        private lateinit var socket: WebSocket
        private val messages = LinkedBlockingQueue<String>()
        private val partial = StringBuilder()
        val closeCode = CompletableFuture<Int>()

        fun send(text: String) {
            socket.sendText(text, true).join()
        }

        fun nextMessage(): String =
            assertNotNull(messages.poll(5, TimeUnit.SECONDS), "no message received")

        fun close() {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join()
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            partial.append(data)
            if (last) {
                messages += partial.toString()
                partial.clear()
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            closeCode.complete(statusCode)
            return null
        }

        companion object {
            fun connect(http: HttpClient, uri: URI): TestDevice {
                val device = TestDevice()
                device.socket = http.newWebSocketBuilder().buildAsync(uri, device).join()
                return device
            }
        }
    }
}
