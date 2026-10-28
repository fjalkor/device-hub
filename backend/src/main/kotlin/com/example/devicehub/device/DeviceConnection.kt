package com.example.devicehub.device

import com.example.devicehub.protocol.DeviceCommand

/**
 * An open connection to a device, as seen by the registry.
 *
 * Keeps the registry independent of the transport (WebSocket today) and lets tests use a fake.
 */
interface DeviceConnection {
    val id: String

    fun send(command: DeviceCommand)

    fun close(reason: String)
}
