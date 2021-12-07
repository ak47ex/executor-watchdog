package com.suenara.executorservicewatchdog

@Suppress("ArrayInDataClass")
data class WatchdogTask(
    val submitThread: String,
    private val submitTime: Long,
    private val startTime: Long = submitTime,
    val stacktrace: Array<StackTraceElement>,
) {
    fun getExecutionTime(currentTimeNanos: Long) = currentTimeNanos - startTime

    fun toString(currentTimeNanos: Long): String {
        return "WatchdogTask(\n    submitThread='$submitThread',\n    executionTime=${getExecutionTime(currentTimeNanos)},\n    stacktrace=\n${stacktrace.drop(1).joinToString("\n    ")}\n)"
    }

}