package com.suenara.executorservicewatchdog

import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService

fun ExecutorService.withWatchdog(
    hangThresholdMillis: Long = 5_000L,
    onHang: (Collection<WatchdogTask>) -> Unit
): ExecutorService = ExecutorServiceWatchdog(this, hangThresholdMillis).apply {
    hangListener = onHang
}

fun ScheduledExecutorService.withWatchdog(
    hangThresholdMillis: Long = 5_000L,
    onHang: (Collection<WatchdogTask>) -> Unit
): ScheduledExecutorService = ScheduledExecutorServiceWatchdog(this, hangThresholdMillis).apply {
    hangListener = onHang
}