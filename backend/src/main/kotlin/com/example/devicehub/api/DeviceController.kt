package com.example.devicehub.api

import com.example.devicehub.device.DeviceNotFoundException
import com.example.devicehub.device.DeviceOfflineException
import com.example.devicehub.device.DeviceRegistry
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
@RequestMapping("/api/devices")
class DeviceController(
    private val registry: DeviceRegistry,
    private val eventStream: DeviceEventStream,
) {

    @GetMapping
    fun devices(): List<DeviceView> = registry.devices().map { it.toView() }

    @GetMapping("/stream")
    fun stream(): SseEmitter = eventStream.subscribe()

    /** Sends the command and returns immediately; the device's ack arrives later via the stream. */
    @PostMapping("/{name}/commands/set-interval")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun setInterval(@PathVariable name: String, @RequestBody request: SetIntervalRequest): CommandView =
        registry.sendSetInterval(name, request.intervalMillis).toView()

    @ExceptionHandler
    fun handle(e: DeviceNotFoundException): ProblemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.message)

    @ExceptionHandler
    fun handle(e: DeviceOfflineException): ProblemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.message)

    @ExceptionHandler
    fun handle(e: IllegalArgumentException): ProblemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.message)
}

