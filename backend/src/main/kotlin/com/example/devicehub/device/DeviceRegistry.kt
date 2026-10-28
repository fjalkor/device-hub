package com.example.devicehub.device

import com.example.devicehub.DeviceHubProperties
import com.example.devicehub.protocol.DeviceCommand
import com.example.devicehub.protocol.DeviceMessage
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Central state of the hub: which devices exist, which are connected and what they last reported.
 *
 * All state changes happen under one lock, which keeps the bookkeeping between devices and
 * connections consistent. Sending, closing connections and publishing events happen outside
 * the lock, so a slow connection or listener cannot block other devices.
 */
@Service
class DeviceRegistry(
    private val clock: Clock,
    private val properties: DeviceHubProperties,
    private val events: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val lock = Any()
    private val deviceStatesByName = HashMap<String, DeviceState>()
    private val connectionsByDeviceName = HashMap<String, DeviceConnection>()
    private val deviceNamesByConnectionId = HashMap<String, String>()

    fun devices(): List<DeviceState> = synchronized(lock) { deviceStatesByName.values.sortedBy { it.name } }

    /**
     * @throws ProtocolViolationException
     * */
    fun register(connection: DeviceConnection, name: String) {
        if (name.isBlank()) throw ProtocolViolationException("name must not be blank")

        val (state, replaced) = synchronized(lock) {
            val registeredAs = deviceNamesByConnectionId[connection.id]
            if (registeredAs != null && registeredAs != name) {
                throw ProtocolViolationException("connection is already registered as '$registeredAs'")
            }
            // A device that reconnects before its old connection was detected as dead takes over.
            val replaced = connectionsByDeviceName.put(name, connection)?.takeIf { it.id != connection.id }
            replaced?.let { deviceNamesByConnectionId.remove(it.id) }
            deviceNamesByConnectionId[connection.id] = name

            val now = clock.instant()
            // The device may have restarted with its default interval, so the old one is no longer known.
            val state = deviceStatesByName[name]?.copy(online = true, lastSeenAt = now, intervalMillis = null)
                ?: DeviceState(name, online = true, lastSeenAt = now)
            deviceStatesByName[name] = state
            state to replaced
        }

        log.info("Device $name registered")
        replaced?.close("replaced by a new connection")
        publish(state)
    }

    fun telemetry(connection: DeviceConnection, telemetry: DeviceMessage.Telemetry) {
        val state = synchronized(lock) {
            val name = deviceNameFor(connection)
            deviceStatesByName.getValue(name)
                .copy(
                    online = true,
                    lastSeenAt = clock.instant(),
                    status = telemetry.status,
                    lastValue = telemetry.value,
                    measuredAt = telemetry.timestamp,
                )
                .also { deviceStatesByName[name] = it }
        }
        publish(state)
    }

    fun ack(connection: DeviceConnection, ack: DeviceMessage.Ack) {
        val state = synchronized(lock) {
            val name = deviceNameFor(connection)
            var deviceState = deviceStatesByName.getValue(name).copy(online = true, lastSeenAt = clock.instant())
            val lastCommandState = deviceState.lastCommandState

            when {
                ack.commandId == null ->
                    log.warn("Device $name could not read a command: ${ack.error}")

                lastCommandState == null || lastCommandState.command.commandId != ack.commandId ->
                    log.warn("Device $name acknowledged unknown or outdated command '${ack.commandId}'")

                ack.error != null ->
                    deviceState = deviceState.copy(lastCommandState = lastCommandState.copy(status = CommandStatus.REJECTED, error = ack.error))

                else -> deviceState = when (val command = lastCommandState.command) {
                    is DeviceCommand.SetInterval -> deviceState.copy(
                        intervalMillis = command.newIntervalMillis,
                        lastCommandState = lastCommandState.copy(status = CommandStatus.ACKNOWLEDGED)
                    )
                }
            }

            deviceState.also { deviceStatesByName[name] = it }
        }
        publish(state)
    }

    fun disconnect(connection: DeviceConnection) {
        val state = synchronized(lock) {
            val name = deviceNamesByConnectionId.remove(connection.id) ?: return
            // Ignore a stale connection that was already replaced by a newer one.
            if (connectionsByDeviceName[name]?.id != connection.id) return
            connectionsByDeviceName.remove(name)
            deviceStatesByName.getValue(name).copy(online = false).also { deviceStatesByName[name] = it }
        }
        log.info("Device '${state.name}' disconnected")
        publish(state)
    }

    /**
     * Sends a set_interval command to the device. The result arrives asynchronously via [ack].
     *
     * @throws DeviceNotFoundException if the device never registered
     * @throws DeviceOfflineException if the device is not connected
     */
    fun sendSetInterval(name: String, intervalMillis: Long): CommandState {
        require(intervalMillis > 0) { "interval must be greater than zero" }

        val command = DeviceCommand.SetInterval(UUID.randomUUID().toString(), intervalMillis)
        val (state, connection) = synchronized(lock) {
            val deviceState = deviceStatesByName[name] ?: throw DeviceNotFoundException(name)
            val connection = connectionsByDeviceName[name] ?: throw DeviceOfflineException(name)
            val commandState = CommandState(
                command = command,
                status = CommandStatus.PENDING,
                sentAt = clock.instant(),
            )
            deviceState.copy(lastCommandState = commandState).also { deviceStatesByName[name] = it } to connection
        }

        publish(state)

        try {
            connection.send(command)
        } catch (e: Exception) {
            // The command stays pending and times out; the dead connection is cleaned up on close.
            log.warn("Sending command to '$name' failed: $e")
            connection.close("send failed")
        }
        return state.lastCommandState!!
    }

    /** Marks silent devices offline and unanswered commands as timed out. Called periodically. */
    fun checkTimeouts() {
        val now = clock.instant()
        val toClose = mutableListOf<DeviceConnection>()
        val changed = synchronized(lock) {
            val changedStates = deviceStatesByName.values.mapNotNull { deviceState ->
                var updated = deviceState
                if (deviceState.online && deviceState.lastSeenAt + offlineAfter(deviceState) < now) {
                    log.info("Device '${deviceState.name}' timed out")
                    updated = updated.copy(online = false)
                    connectionsByDeviceName.remove(deviceState.name)?.let {
                        deviceNamesByConnectionId.remove(it.id)
                        toClose += it
                    }
                }
                val command = deviceState.lastCommandState
                if (command?.status == CommandStatus.PENDING && command.sentAt + properties.commandTimeout < now) {
                    updated = updated.copy(lastCommandState = command.copy(status = CommandStatus.TIMED_OUT))
                }
                updated.takeIf { it != deviceState }
            }

            changedStates.forEach { deviceStatesByName[it.name] = it }
            changedStates
        }

        toClose.forEach { it.close("no message received in time") }
        changed.forEach { publish(it) }
    }

    private fun offlineAfter(device: DeviceState): Duration {
        val byInterval = device.intervalMillis?.let { Duration.ofMillis(it * 3) } ?: Duration.ZERO
        return maxOf(properties.offlineAfter, byInterval)
    }

    private fun deviceNameFor(connection: DeviceConnection): String =
        deviceNamesByConnectionId[connection.id] ?: throw ProtocolViolationException("device must register first")

    private fun publish(state: DeviceState) = events.publishEvent(DeviceChanged(state))
}
