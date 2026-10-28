package com.example.simulator

import com.example.simulator.command.Command
import com.example.simulator.command.jsonToCommand
import com.example.simulator.message.Message
import com.example.simulator.result.ParseResult
import org.slf4j.LoggerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.random.Random

class Simulator(
    private val client: OkHttpClient,
    private val id: Int,
    private val hubUrl: String,
    private val json: Json,
    private val behavior: Behavior = Behavior.NORMAL,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var telemetryJob: Job? = null

    @Volatile
    private var intervalMillis = 5_000L

    private var backoffMillis = 1_000L

    /** Set once a HANGING device stops responding; from then on it does nothing at all. */
    @Volatile
    private var hung = false

    fun start() {
        client.newWebSocket(
            request = Request.Builder().url(hubUrl).build(),
            listener = constructListener(
                onOpen = this::onOpen,
                onMessage = this::onMessage,
                onClosing = this::onClosing,
                onClosed = this::onClosed,
                onFailure = this::onFailure,
            )
        )
    }

    private fun onOpen(ws: WebSocket, response: Response) {
        backoffMillis = MINIMUM_BACKOFF_MILLIS
        ws.send(Message.Register("device-${id}").asJson(json))

        startTelemetry(ws)
    }

    private fun onMessage(ws: WebSocket, text: String) {
        if (behavior == Behavior.DEAF || hung) return

        when (val result = text.jsonToCommand(json)) {
            is ParseResult.InvalidJSON -> ws.send(Message.Ack(error = result.error).asJson(json))
            is ParseResult.InvalidValue -> ws.send(Message.Ack(commandId = result.commandId, error = result.error).asJson(json))
            is ParseResult.Success if behavior == Behavior.REJECTING ->
                ws.send(Message.Ack(commandId = result.command.commandId, error = "device is in maintenance mode").asJson(json))
            is ParseResult.Success -> {
                execute(result.command)
                ws.send(Message.Ack(commandId = result.command.commandId).asJson(json))
            }
        }
    }

    private fun onClosing(ws: WebSocket, code: Int, reason: String) {
        ws.close(1000, null)
        telemetryJob?.cancel()
    }

    private fun onClosed(ws: WebSocket, code: Int, reason: String) {
        logger.info("[$id] closed: $code $reason")
        scheduleReconnect()
    }

    private fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
        logger.info("[$id] failed: ${t.localizedMessage}")
        scheduleReconnect()
    }

    private fun startTelemetry(ws: WebSocket) {
        telemetryJob?.cancel()

        telemetryJob = scope.launch {
            if (behavior == Behavior.FLAKY) {
                launch {
                    delay(Random.nextLong(FLAKY_MIN_UPTIME_MILLIS, FLAKY_MAX_UPTIME_MILLIS))
                    logger.info("[$id] flaky: dropping the connection")
                    ws.cancel()
                }
            }

            var sent = 0
            while (isActive) {
                if (behavior == Behavior.HANGING && sent == HANGING_AFTER_MESSAGES) {
                    logger.info("[$id] hanging: no more messages, connection stays open")
                    hung = true
                    return@launch
                }
                ws.send(Message.Telemetry(status = "active", value = Random.nextDouble(0.0, 100.0)).asJson(json))
                sent++
                delay(intervalMillis)
            }
        }
    }

    private fun scheduleReconnect() {
        telemetryJob?.cancel()
        if (hung) return

        val wait = (backoffMillis * Random.nextDouble(0.8, 1.2)).toLong()
        backoffMillis = (backoffMillis * 2).coerceAtMost(MAXIMUM_BACKOFF_MILLIS)
        logger.info("[$id] reconnect in $wait ms")
        scope.launch {
            delay(wait)
            start()
        }
    }

    private fun execute(command: Command) = when (command) {
        is Command.SetInterval -> intervalMillis = command.newIntervalMillis
    }

    private fun constructListener(
        onOpen: (WebSocket, Response) -> Unit,
        onMessage: (WebSocket, String) -> Unit,
        onClosing: (WebSocket, Int, String) -> Unit,
        onClosed: (WebSocket, Int, String) -> Unit,
        onFailure: (WebSocket, Throwable, Response?) -> Unit,
    ) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = onOpen(webSocket, response)
        override fun onMessage(webSocket: WebSocket, text: String) = onMessage(webSocket, text)
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = onClosing(webSocket, code, reason)
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onClosed(webSocket, code, reason)
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
            onFailure(webSocket, t, response)
    }

    companion object {
        private const val MAXIMUM_BACKOFF_MILLIS = 30_000L
        private const val MINIMUM_BACKOFF_MILLIS = 1_000L
        private const val HANGING_AFTER_MESSAGES = 3
        private const val FLAKY_MIN_UPTIME_MILLIS = 10_000L
        private const val FLAKY_MAX_UPTIME_MILLIS = 20_000L
    }
}

