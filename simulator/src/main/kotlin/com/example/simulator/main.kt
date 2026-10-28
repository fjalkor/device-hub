package com.example.simulator

import org.slf4j.LoggerFactory
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; classDiscriminator = "type" }

/**
 * Entry point: starts a number of simulated devices that connect to the hub.
 *
 * The simulator deliberately shares no code with the backend. It only knows the
 * wire protocol (JSON over WebSocket) documented in the README, like a real device would.
 */
fun main(args: Array<String>) {
    val logger = LoggerFactory.getLogger(Simulator::class.java)

    val hubUrl = args.getOrNull(0) ?: "ws://localhost:8080/ws/device"
    val deviceCount = args.getOrNull(1)?.toIntOrNull() ?: 10

    logger.info("Simulator: $deviceCount devices -> $hubUrl")

    val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    repeat(deviceCount) {
        val behavior = behaviorFor(it, deviceCount)
        if (behavior != Behavior.NORMAL) logger.info("device-$it is $behavior")
        Simulator(client, it, hubUrl, json, behavior).start()
    }

    Thread.currentThread().join()
}
