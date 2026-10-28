package com.example.devicehub.websocket

import com.example.devicehub.device.DeviceConnection
import com.example.devicehub.protocol.DeviceCommand
import com.example.devicehub.protocol.ProtocolCodec
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator

/**
 * [DeviceConnection] backed by a WebSocket session.
 *
 * Spring's WebSocketSession must not be written to from several threads at once, but commands
 * (HTTP request thread) and closes (scheduler, WebSocket thread) can happen concurrently. The decorator
 * serializes those writes and drops the connection if a device stops reading.
 */
class WebSocketDeviceConnection(session: WebSocketSession, private val codec: ProtocolCodec) : DeviceConnection {

    private val session = ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MILLIS, BUFFER_SIZE_LIMIT_BYTES)

    override val id: String = session.id

    override fun send(command: DeviceCommand) {
        session.sendMessage(TextMessage(codec.encode(command)))
    }

    override fun close(reason: String) {
        close(CloseStatus.NORMAL.withReason(reason))
    }

    fun close(status: CloseStatus) {
        if (session.isOpen) session.close(status)
    }

    private companion object {
        const val SEND_TIME_LIMIT_MILLIS = 5_000
        const val BUFFER_SIZE_LIMIT_BYTES = 64 * 1024
    }
}
