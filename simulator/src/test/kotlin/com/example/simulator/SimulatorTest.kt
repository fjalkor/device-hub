package com.example.simulator

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/** Runs a real Simulator against a local WebSocket server that plays the hub. */
class SimulatorTest {

    private val server = MockWebServer()
    private val hub = FakeHub()

    @BeforeTest
    fun setUp() {
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.close()
    }

    @Test
    fun `registers and sends telemetry after connecting`() {
        startSimulator(id = 7)

        val register = hub.nextMessage()
        assertEquals(JsonPrimitive("register"), register["type"])
        assertEquals(JsonPrimitive("device-7"), register["name"])
        assertEquals(JsonPrimitive("telemetry"), hub.nextMessage()["type"])
    }

    @Test
    fun `acknowledges a valid command`() {
        startSimulator()
        hub.skipUntil("telemetry")

        hub.send("""{"type":"set_interval","command_id":"c-1","new_interval_millis":1000}""")

        val ack = hub.skipUntil("ack")
        assertEquals(JsonPrimitive("c-1"), ack["command_id"])
        assertEquals(true, ack["error"] == null || ack["error"] == JsonNull)
    }

    @Test
    fun `rejects an invalid interval with an error ack`() {
        startSimulator()
        hub.skipUntil("telemetry")

        hub.send("""{"type":"set_interval","command_id":"c-1","new_interval_millis":-5}""")

        val ack = hub.skipUntil("ack")
        assertEquals(JsonPrimitive("c-1"), ack["command_id"])
        assertNotNull(ack["error"] as? JsonPrimitive)
    }

    @Test
    fun `answers an unreadable command with an ack without command id`() {
        startSimulator()
        hub.skipUntil("telemetry")

        hub.send("{garbage")

        val ack = hub.skipUntil("ack")
        assertEquals(true, ack["command_id"] == null || ack["command_id"] == JsonNull)
        assertNotNull(ack["error"] as? JsonPrimitive)
    }

    @Test
    fun `reconnects and registers again after the hub closes the connection`() {
        startSimulator(connections = 2)
        hub.skipUntil("register")

        hub.close()

        // The first reconnect waits about one second (backoff with jitter).
        hub.skipUntil("register", timeoutSeconds = 5)
    }

    private fun startSimulator(id: Int = 1, connections: Int = 1) {
        repeat(connections) { server.enqueue(MockResponse.Builder().webSocketUpgrade(hub).build()) }
        val url = server.url("/ws/device").toString().replaceFirst("http", "ws")
        Simulator(OkHttpClient(), id, url, json).start()
    }

    /** Server side of the connection: records what the simulator sends. */
    private class FakeHub : WebSocketListener() {
        private val messages = LinkedBlockingQueue<String>()

        @Volatile
        private var socket: WebSocket? = null

        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            socket = webSocket
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            messages += text
        }

        fun send(text: String) {
            checkNotNull(socket) { "simulator is not connected" }.send(text)
        }

        fun close() {
            checkNotNull(socket).close(1001, "going away")
        }

        fun nextMessage(timeoutSeconds: Long = 3): JsonObject =
            messages.poll(timeoutSeconds, TimeUnit.SECONDS)?.toJsonObject() ?: fail("no message within $timeoutSeconds s")

        /** Skips telemetry and other messages until one of the given type arrives. */
        fun skipUntil(type: String, timeoutSeconds: Long = 3): JsonObject {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            while (System.nanoTime() < deadline) {
                val message = nextMessage(timeoutSeconds)
                if (message["type"] == JsonPrimitive(type)) return message
            }
            fail("no '$type' message within $timeoutSeconds s")
        }
    }
}
