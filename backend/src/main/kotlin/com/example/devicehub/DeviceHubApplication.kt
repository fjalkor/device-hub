package com.example.devicehub

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class DeviceHubApplication

fun main(args: Array<String>) {
    runApplication<DeviceHubApplication>(*args)
}
