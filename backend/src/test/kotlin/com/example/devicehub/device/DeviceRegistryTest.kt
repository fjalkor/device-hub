package com.example.devicehub.device

import com.example.devicehub.DeviceHubProperties
import com.example.devicehub.protocol.DeviceCommand
import com.example.devicehub.protocol.DeviceMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceRegistryTest {

    private val clock = MutableClock(Instant.parse("2026-10-28T10:00:00Z"))
    private val events = mutableListOf<DeviceChanged>()
    private val registry = DeviceRegistry(
        clock = clock,
        properties = DeviceHubProperties(offlineAfter = Duration.ofSeconds(15), commandTimeout = Duration.ofSeconds(10)),
        events = ApplicationEventPublisher { events += it as DeviceChanged },
    )

    private fun device(name: String) = registry.devices().single { it.name == name }

    @Test
    fun `register marks device online and publishes it`() {
        registry.register(FakeConnection("c1"), "device-1")

        assertTrue(device("device-1").online)
        assertEquals("device-1", events.last().device.name)
    }

    @Test
    fun `telemetry before register is a protocol violation`() {
        assertThrows<ProtocolViolationException> {
            registry.telemetry(FakeConnection("c1"), telemetry(1.0))
        }
    }

    @Test
    fun `telemetry updates last value`() {
        val connection = FakeConnection("c1")
        registry.register(connection, "device-1")

        registry.telemetry(connection, telemetry(21.5))

        assertEquals(21.5, device("device-1").lastValue)
        assertEquals("active", device("device-1").status)
    }

    @Test
    fun `disconnect marks device offline but keeps last value`() {
        val connection = FakeConnection("c1")
        registry.register(connection, "device-1")
        registry.telemetry(connection, telemetry(21.5))

        registry.disconnect(connection)

        assertFalse(device("device-1").online)
        assertEquals(21.5, device("device-1").lastValue)
    }

    @Test
    fun `reconnect replaces old connection and stale disconnect is ignored`() {
        val old = FakeConnection("c1")
        val new = FakeConnection("c2")
        registry.register(old, "device-1")

        registry.register(new, "device-1")
        registry.disconnect(old)

        assertTrue(old.closed)
        assertTrue(device("device-1").online)
    }

    @Test
    fun `command to unknown or offline device fails`() {
        assertThrows<DeviceNotFoundException> { registry.sendSetInterval("device-1", 1000) }

        val connection = FakeConnection("c1")
        registry.register(connection, "device-1")
        registry.disconnect(connection)

        assertThrows<DeviceOfflineException> { registry.sendSetInterval("device-1", 1000) }
    }

    @Test
    fun `set interval is sent and acknowledged`() {
        val connection = FakeConnection("c1")
        registry.register(connection, "device-1")

        val commandState = registry.sendSetInterval("device-1", 2000)

        assertEquals(CommandStatus.PENDING, commandState.status)
        assertEquals(DeviceCommand.SetInterval(commandState.command.commandId, 2000), connection.sent.single())

        registry.ack(connection, DeviceMessage.Ack(commandState.command.commandId))

        assertEquals(CommandStatus.ACKNOWLEDGED, device("device-1").lastCommandState?.status)
        assertEquals(2000, device("device-1").intervalMillis)
    }

    @Test
    fun `immediate ack is published after the pending state`() {
        lateinit var connection: FakeConnection
        // The device answers while send() is still running, like an ack on another thread would.
        connection = FakeConnection("c1") { registry.ack(connection, DeviceMessage.Ack(it.commandId)) }
        registry.register(connection, "device-1")

        registry.sendSetInterval("device-1", 2000)

        assertEquals(CommandStatus.ACKNOWLEDGED, events.last().device.lastCommandState?.status)
    }

    @Test
    fun `rejected command keeps interval unchanged`() {
        val connection = FakeConnection("c1")
        registry.register(connection, "device-1")
        val commandState = registry.sendSetInterval("device-1", 2000)

        registry.ack(connection, DeviceMessage.Ack(commandState.command.commandId, "not allowed"))

        assertEquals(CommandStatus.REJECTED, device("device-1").lastCommandState?.status)
        assertEquals("not allowed", device("device-1").lastCommandState?.error)
        assertNull(device("device-1").intervalMillis)
    }

    @Test
    fun `interval is unknown again after the device reconnects`() {
        val old = FakeConnection("c1")
        registry.register(old, "device-1")
        val commandState = registry.sendSetInterval("device-1", 2000)
        registry.ack(old, DeviceMessage.Ack(commandState.command.commandId))
        registry.disconnect(old)

        registry.register(FakeConnection("c2"), "device-1")

        assertNull(device("device-1").intervalMillis)
    }

    @Test
    fun `offline timeout falls back to the default after the device reconnects`() {
        val old = FakeConnection("c1")
        registry.register(old, "device-1")
        val commandState = registry.sendSetInterval("device-1", 60_000)
        registry.ack(old, DeviceMessage.Ack(commandState.command.commandId))
        registry.disconnect(old)
        val new = FakeConnection("c2")
        registry.register(new, "device-1")

        clock.advance(Duration.ofSeconds(16))
        registry.checkTimeouts()

        assertFalse(device("device-1").online)
        assertTrue(new.closed)
    }

    @Test
    fun `non-positive interval is rejected`() {
        registry.register(FakeConnection("c1"), "device-1")

        assertThrows<IllegalArgumentException> { registry.sendSetInterval("device-1", 0) }
    }

    @Test
    fun `silent device goes offline after timeout and its connection is closed`() {
        val connection = FakeConnection("c1")
        registry.register(connection, "device-1")

        clock.advance(Duration.ofSeconds(15))
        registry.checkTimeouts()
        assertTrue(device("device-1").online)

        clock.advance(Duration.ofSeconds(1))
        registry.checkTimeouts()
        assertFalse(device("device-1").online)
        assertTrue(connection.closed)
    }

    @Test
    fun `offline timeout grows with a long interval`() {
        val connection = FakeConnection("c1")
        registry.register(connection, "device-1")
        val commandState = registry.sendSetInterval("device-1", 60_000)
        registry.ack(connection, DeviceMessage.Ack(commandState.command.commandId))

        clock.advance(Duration.ofSeconds(179))
        registry.checkTimeouts()

        assertTrue(device("device-1").online)
    }

    @Test
    fun `unanswered command times out`() {
        val connection = FakeConnection("c1")
        registry.register(connection, "device-1")
        registry.sendSetInterval("device-1", 2000)

        clock.advance(Duration.ofSeconds(5))
        registry.telemetry(connection, telemetry(1.0))
        clock.advance(Duration.ofSeconds(6))
        registry.checkTimeouts()

        assertEquals(CommandStatus.TIMED_OUT, device("device-1").lastCommandState?.status)
        assertTrue(device("device-1").online)
    }

    private fun telemetry(value: Double) = DeviceMessage.Telemetry("active", value, clock.instant())

    private class FakeConnection(
        override val id: String,
        private val onSend: (DeviceCommand) -> Unit = {},
    ) : DeviceConnection {
        val sent = mutableListOf<DeviceCommand>()
        var closed = false

        override fun send(command: DeviceCommand) {
            sent += command
            onSend(command)
        }

        override fun close(reason: String) {
            closed = true
        }
    }

    private class MutableClock(private var now: Instant) : Clock() {
        fun advance(duration: Duration) {
            now += duration
        }

        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}
