package com.example.devicehub.device

import com.example.devicehub.protocol.DeviceCommand
import java.time.Instant

/** Snapshot of everything the hub knows about one device. Immutable; the registry replaces it on every change. */
data class DeviceState(
    val name: String,
    val online: Boolean,
    /** Server time of the last message from the device. */
    val lastSeenAt: Instant,
    val status: String? = null,
    val lastValue: Double? = null,
    /** Device time of [lastValue]. */
    val measuredAt: Instant? = null,
    /** Measuring interval, known once the device has acknowledged a set_interval command since it registered. */
    val intervalMillis: Long? = null,
    val lastCommandState: CommandState? = null,
)

data class CommandState(
    val command: DeviceCommand,
    val status: CommandStatus,
    val sentAt: Instant,
    val error: String? = null,
)

enum class CommandStatus { PENDING, ACKNOWLEDGED, REJECTED, TIMED_OUT }

/** Published whenever a device's state changes. */
data class DeviceChanged(val device: DeviceState)
