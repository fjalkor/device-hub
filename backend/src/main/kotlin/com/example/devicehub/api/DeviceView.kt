package com.example.devicehub.api

import java.time.Instant

/**
 * What the HTTP API and the event stream expose about a device.
 *
 * Kept separate from [com.example.devicehub.device.DeviceState] so the device protocol types never leak into the API
 * and either side can change without breaking the other.
 */
data class DeviceView(
    val name: String,
    val online: Boolean,
    val lastSeenAt: Instant,
    val status: String?,
    val lastValue: Double?,
    val measuredAt: Instant?,
    val intervalMillis: Long?,
    val lastCommand: CommandView?,
)