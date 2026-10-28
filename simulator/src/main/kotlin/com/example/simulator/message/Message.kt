package com.example.simulator.message

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

@Serializable
sealed class Message {
    @Serializable
    @SerialName("register")
    data class Register(
        @SerialName("name")
        val name: String,
    ) : Message()

    @Serializable
    @SerialName("telemetry")
    data class Telemetry(
        @SerialName("status")
        val status: String,
        @SerialName("value")
        val value: Double,
        @SerialName("timestamp")
        val timestamp: String = Clock.System.now().toString(),
    ): Message()

    @Serializable
    @SerialName("ack")
    data class Ack(
        @SerialName("command_id")
        val commandId: String? = null,
        @SerialName("error")
        val error: String? = null,
    ): Message()

    fun asJson(json: Json) = json.encodeToString(this)
}