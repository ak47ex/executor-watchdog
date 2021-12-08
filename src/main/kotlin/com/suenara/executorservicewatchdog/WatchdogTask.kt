package com.suenara.executorservicewatchdog

import java.util.concurrent.TimeUnit

@Suppress("ArrayInDataClass")
data class WatchdogTask(
    val submitThread: String,
    private val submitTime: Long,
    private val startTime: Long = submitTime,
    val stacktrace: Collection<StackTraceElement>,
) {
    fun getExecutionTimeNs(currentTimeNanos: Long) = currentTimeNanos - startTime

    fun toString(currentTimeNanos: Long): String {
        return "WatchdogTask(\n    submitThread='$submitThread',\n    executionTime=${TimeUnit.NANOSECONDS.toMillis(getExecutionTimeNs(currentTimeNanos))}ms,\n    stacktrace=\n    ${stacktrace.joinToString("\n    ")}\n)"
    }

}