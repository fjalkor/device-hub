package com.example.devicehub.api

import com.example.devicehub.device.CommandStatus
import java.time.Instant

data class CommandView(
    val commandId: String,
    val description: String,
    val status: CommandStatus,
    val sentAt: Instant,
    val error: String?,
)