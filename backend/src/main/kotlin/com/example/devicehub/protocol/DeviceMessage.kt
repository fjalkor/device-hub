package com.example.devicehub.protocol

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.Instant

/**
 * Messages a device sends to the hub. The wire format is documented in the README;
 * these types are the backend's own view of it and are not shared with any device.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = DeviceMessage.Register::class, name = "register"),
    JsonSubTypes.Type(value = DeviceMessage.Telemetry::class, name = "telemetry"),
    JsonSubTypes.Type(value = DeviceMessage.Ack::class, name = "ack"),
)
sealed interface DeviceMessage {

    data class Register(
        val name: String,
    ) : DeviceMessage

    data class Telemetry(
        val status: String,
        val value: Double,
        val timestamp: Instant,
    ) : DeviceMessage

    /** Answer to a [DeviceCommand]. No [error] means the command was executed. */
    data class Ack(
        @JsonProperty("command_id")
        val commandId: String? = null,
        val error: String? = null,
    ) : DeviceMessage
}
