package com.example.devicehub.protocol

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/** Commands the hub sends to a device. Every command is answered with exactly one [DeviceMessage.Ack]. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = DeviceCommand.SetInterval::class, name = "set_interval"),
)
sealed interface DeviceCommand {
    val commandId: String

    data class SetInterval(
        @JsonProperty("command_id")
        override val commandId: String,
        @JsonProperty("new_interval_millis")
        val newIntervalMillis: Long,
    ) : DeviceCommand
}
