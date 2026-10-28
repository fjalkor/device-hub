package com.example.devicehub.websocket

import com.example.devicehub.device.DeviceRegistry
import com.example.devicehub.device.ProtocolViolationException
import com.example.devicehub.protocol.DeviceMessage
import com.example.devicehub.protocol.InvalidMessageException
import com.example.devicehub.protocol.ProtocolCodec
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.concurrent.ConcurrentHashMap

/**
 * Entry point for device connections on `/ws/device`.
 *
 * Unreadable messages are logged and ignored, so one bad message does not disconnect a device.
 * Protocol violations (e.g. telemetry before register) close the connection with 1008.
 */
@Component
class DeviceWebSocketHandler(
    private val registry: DeviceRegistry,
    private val codec: ProtocolCodec,
) : TextWebSocketHandler() {

    private val log = LoggerFactory.getLogger(javaClass)
    private val connections = ConcurrentHashMap<String, WebSocketDeviceConnection>()

    override fun afterConnectionEstablished(session: WebSocketSession) {
        connections[session.id] = WebSocketDeviceConnection(session, codec)
        log.debug("Connection {} opened from {}", session.id, session.remoteAddress)
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val connection = connections[session.id] ?: return
        try {
            when (val msg = codec.decode(message.payload)) {
                is DeviceMessage.Register -> registry.register(connection, msg.name)
                is DeviceMessage.Telemetry -> registry.telemetry(connection, msg)
                is DeviceMessage.Ack -> registry.ack(connection, msg)
            }
        } catch (e: InvalidMessageException) {
            log.warn("Ignoring invalid message on connection {}: {}", session.id, e.message)
        } catch (e: ProtocolViolationException) {
            log.warn("Closing connection {}: {}", session.id, e.message)
            connection.close(CloseStatus.POLICY_VIOLATION.withReason(e.message ?: "protocol violation"))
        }
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        connections.remove(session.id)?.let(registry::disconnect)
        log.debug("Connection {} closed: {}", session.id, status)
    }

    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
        log.warn("Transport error on connection {}: {}", session.id, exception.message)
    }
}
