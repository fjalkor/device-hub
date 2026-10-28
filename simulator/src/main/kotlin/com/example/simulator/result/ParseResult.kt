package com.example.simulator.result

import com.example.simulator.command.Command

sealed interface ParseResult {
    data class Success(val command: Command) : ParseResult
    data class InvalidJSON(val error: String) : ParseResult
    data class InvalidValue(val commandId: String, val error: String) : ParseResult
}