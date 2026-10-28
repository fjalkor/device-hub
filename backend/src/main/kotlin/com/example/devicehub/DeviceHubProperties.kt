package com.example.devicehub

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("device-hub")
data class DeviceHubProperties(
    /** A device is marked offline after this long without a message (or 3x its interval, if larger). */
    val offlineAfter: Duration = Duration.ofSeconds(15),
    /** A command without ack is marked timed out after this long. */
    val commandTimeout: Duration = Duration.ofSeconds(10),
)
