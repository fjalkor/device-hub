package com.example.simulator.message

import com.example.simulator.json
import com.example.simulator.toJsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageTest {

    @Test
    fun `register matches the protocol`() {
        val message = Message.Register("device-1").asJson(json).toJsonObject()

        assertEquals(JsonPrimitive("register"), message["type"])
        assertEquals(JsonPrimitive("device-1"), message["name"])
    }

    @Test
    fun `telemetry carries value and an ISO-8601 timestamp`() {
        val message = Message.Telemetry(status = "active", value = 42.5).asJson(json).toJsonObject()

        assertEquals(JsonPrimitive("telemetry"), message["type"])
        assertEquals(JsonPrimitive("active"), message["status"])
        assertEquals(JsonPrimitive(42.5), message["value"])
        Instant.parse((message["timestamp"] as JsonPrimitive).content)   // throws if not ISO-8601
    }

    @Test
    fun `successful ack has the command id and no error`() {
        val message = Message.Ack(commandId = "c-1").asJson(json).toJsonObject()

        assertEquals(JsonPrimitive("ack"), message["type"])
        assertEquals(JsonPrimitive("c-1"), message["command_id"])
        assertTrue(message["error"] == null || message["error"] == JsonNull)
    }

    @Test
    fun `failed ack carries the error`() {
        val message = Message.Ack(commandId = "c-1", error = "nope").asJson(json).toJsonObject()

        assertEquals(JsonPrimitive("nope"), message["error"])
    }
}
