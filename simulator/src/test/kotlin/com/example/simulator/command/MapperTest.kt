package com.example.simulator.command

import com.example.simulator.result.ParseResult
import com.example.simulator.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MapperTest {

    @Test
    fun `parses set_interval`() {
        val result = """{"type":"set_interval","command_id":"c-1","new_interval_millis":2000}""".jsonToCommand(json)

        assertEquals(ParseResult.Success(Command.SetInterval("c-1", 2000)), result)
    }

    @Test
    fun `ignores unknown fields`() {
        val result = """{"type":"set_interval","command_id":"c-1","new_interval_millis":2000,"extra":true}""".jsonToCommand(json)

        assertIs<ParseResult.Success>(result)
    }

    @Test
    fun `rejects non-positive interval with the command id`() {
        val result = """{"type":"set_interval","command_id":"c-1","new_interval_millis":0}""".jsonToCommand(json)

        assertIs<ParseResult.InvalidValue>(result)
        assertEquals("c-1", result.commandId)
    }

    @Test
    fun `rejects malformed json`() {
        assertIs<ParseResult.InvalidJSON>("{not json".jsonToCommand(json))
    }

    @Test
    fun `rejects unknown command type`() {
        assertIs<ParseResult.InvalidJSON>("""{"type":"reboot","command_id":"c-1"}""".jsonToCommand(json))
    }

    @Test
    fun `rejects missing required field`() {
        assertIs<ParseResult.InvalidJSON>("""{"type":"set_interval","command_id":"c-1"}""".jsonToCommand(json))
    }

    @Test
    fun `rejects wrong field type`() {
        val result = """{"type":"set_interval","command_id":"c-1","new_interval_millis":"fast"}""".jsonToCommand(json)

        assertIs<ParseResult.InvalidJSON>(result)
    }
}
