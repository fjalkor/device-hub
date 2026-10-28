package com.example.devicehub.device

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * A device that loses power or network often leaves a half-open TCP connection behind,
 * so no close event ever arrives. This job catches those devices by their silence.
 */
@Component
class DeviceTimeoutJob(private val registry: DeviceRegistry) {

    @Scheduled(fixedDelayString = "\${device-hub.timeout-check-interval:2s}")
    fun checkTimeouts() = registry.checkTimeouts()
}
