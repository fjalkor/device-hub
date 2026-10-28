package com.example.devicehub.protocol

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import kotlin.test.assertEquals

class ProtocolCodecTest {

    private val codec = ProtocolCodec()

    @Test
    fun `decodes register`() {
        assertEquals(
            DeviceMessage.Register("device-1"),
            codec.decode("""{"type":"register","name":"device-1"}"""),
        )
    }

    @Test
    fun `decodes telemetry and ignores unknown fields`() {
        assertEquals(
            DeviceMessage.Telemetry("active", 42.5, Instant.parse("2026-10-28T09:41:23.512Z")),
            codec.decode("""{"type":"telemetry","status":"active","value":42.5,"timestamp":"2026-10-28T09:41:23.512Z","extra":1}"""),
        )
    }

    @Test
    fun `decodes ack with and without error`() {
        assertEquals(DeviceMessage.Ack("c-1"), codec.decode("""{"type":"ack","command_id":"c-1"}"""))
        assertEquals(
            DeviceMessage.Ack(null, "invalid json"),
            codec.decode("""{"type":"ack","error":"invalid json"}"""),
        )
    }

    @Test
    fun `rejects invalid messages`() {
        listOf(
            "not json",
            """{"name":"device-1"}""",
            """{"type":"unknown"}""",
            """{"type":"register"}""",
            """{"type":"telemetry","status":"active","timestamp":"2026-10-28T09:41:23Z"}""",
            """{"type":"telemetry","status":"active","value":1.0,"timestamp":"yesterday"}""",
        ).forEach { text ->
            assertThrows<InvalidMessageException>(text) { codec.decode(text) }
        }
    }

    @Test
    fun `encodes set_interval`() {
        assertEquals(
            """{"type":"set_interval","command_id":"c-1","new_interval_millis":2000}""",
            codec.encode(DeviceCommand.SetInterval("c-1", 2000)),
        )
    }
}
