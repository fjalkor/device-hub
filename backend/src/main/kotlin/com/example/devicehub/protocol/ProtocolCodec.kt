package com.example.devicehub.protocol

import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

/**
 * Converts between the JSON wire format and the protocol types.
 *
 * Uses its own mapper instead of Spring's, so the device protocol does not change
 * when someone reconfigures JSON for the REST API.
 */
@Component
class ProtocolCodec {

    private val mapper = JsonMapper.builder()
        .addModule(kotlinModule())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .build()

    private val commandWriter = mapper.writerFor(DeviceCommand::class.java)

    /** @throws InvalidMessageException if [text] is not a valid device message. */
    fun decode(text: String): DeviceMessage = try {
        mapper.readValue(text, DeviceMessage::class.java)
    } catch (e: JacksonException) {
        throw InvalidMessageException(e.localizedMessage ?: "invalid message", e)
    }

    fun encode(command: DeviceCommand): String = commandWriter.writeValueAsString(command)
}
