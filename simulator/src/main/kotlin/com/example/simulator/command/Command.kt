package com.example.simulator.command

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class Command {
    abstract val commandId: String

    @Serializable
    @SerialName("set_interval")
    data class SetInterval(
        @SerialName("command_id")
        override val commandId: String,
        @SerialName("new_interval_millis")
        val newIntervalMillis: Long,
    ) : Command()
}
