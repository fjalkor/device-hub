package com.example.simulator.command

import com.example.simulator.result.ParseResult
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

fun String.jsonToCommand(json: Json): ParseResult {
    val cmd = try {
        json.decodeFromString<Command>(this)
    } catch (e: SerializationException) {
        return ParseResult.InvalidJSON(e.localizedMessage ?: "invalid JSON")
    }

    return when (cmd) {
        is Command.SetInterval -> {
            if (cmd.newIntervalMillis <= 0) {
                ParseResult.InvalidValue(
                    commandId = cmd.commandId,
                    error = "new interval must be greater than zero",
                )
            } else {
                ParseResult.Success(cmd)
            }
        }
    }
}