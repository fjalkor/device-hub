package com.example.devicehub.api

import com.example.devicehub.device.DeviceChanged
import com.example.devicehub.device.DeviceRegistry
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Pushes device changes to browsers via Server-Sent Events.
 *
 * A new subscriber first gets a `snapshot` event with all devices, then one `device` event per change.
 */
@Component
class DeviceEventStream(private val registry: DeviceRegistry) {

    private val emitters = CopyOnWriteArrayList<SseEmitter>()

    fun subscribe(): SseEmitter {
        val emitter = SseEmitter(NO_TIMEOUT)
        emitter.onCompletion { emitters.remove(emitter) }
        emitter.onTimeout { emitters.remove(emitter) }
        emitter.onError { emitters.remove(emitter) }
        emitters += emitter

        send(emitter, SseEmitter.event().name("snapshot").data(registry.devices().map { it.toView() }))
        return emitter
    }

    @EventListener
    fun onDeviceChanged(event: DeviceChanged) {
        emitters.forEach { send(it, SseEmitter.event().name("device").data(event.device.toView())) }
    }

    /** Keeps idle connections alive through proxies and detects browsers that went away. */
    @Scheduled(fixedRate = HEARTBEAT_MILLIS)
    fun heartbeat() {
        emitters.forEach { send(it, SseEmitter.event().comment("heartbeat")) }
    }

    /** Open streams would otherwise hold up graceful shutdown. */
    @EventListener(ContextClosedEvent::class)
    fun closeAll() {
        emitters.forEach { it.complete() }
    }

    private fun send(emitter: SseEmitter, event: SseEmitter.SseEventBuilder) {
        try {
            emitter.send(event)
        } catch (e: Exception) {
            // The browser is gone; Spring completes the emitter on its own.
            emitters.remove(emitter)
        }
    }

    private companion object {
        const val NO_TIMEOUT = 0L
        const val HEARTBEAT_MILLIS = 15_000L
    }
}
