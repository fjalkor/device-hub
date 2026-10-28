package com.example.simulator

/** How a simulated device behaves, so the UI can show every state a real device can get into. */
enum class Behavior {
    /** Sends telemetry and executes commands. */
    NORMAL,

    /** Answers every command with an error ack. */
    REJECTING,

    /** Ignores commands and never acks them. */
    DEAF,

    /** Stops sending after a few messages but keeps the connection open, like a hung device. */
    HANGING,

    /** Drops its connection every few seconds and reconnects. */
    FLAKY,
}

/** The last devices get one faulty behavior each, all others behave normally. */
fun behaviorFor(index: Int, deviceCount: Int): Behavior {
    val faulty = Behavior.entries - Behavior.NORMAL
    return faulty.getOrNull(index - (deviceCount - faulty.size)) ?: Behavior.NORMAL
}
