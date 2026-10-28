package com.example.devicehub.api

import com.example.devicehub.device.CommandState
import com.example.devicehub.device.DeviceState
import com.example.devicehub.protocol.DeviceCommand

fun DeviceState.toView() = DeviceView(
    name = name,
    online = online,
    lastSeenAt = lastSeenAt,
    status = status,
    lastValue = lastValue,
    measuredAt = measuredAt,
    intervalMillis = intervalMillis,
    lastCommand = lastCommandState?.toView(),
)

fun CommandState.toView(): CommandView = CommandView(
    commandId = command.commandId,
    description = when (command) {
        is DeviceCommand.SetInterval -> "Intervall ${command.newIntervalMillis} ms"
    },
    status = status,
    sentAt = sentAt,
    error = error,
)
