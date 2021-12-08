package com.suenara.executorservicewatchdog

import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService

fun ExecutorService.withWatchdog(
    stuckThresholdMillis: Long = 5_000L,
    hangThresholdMillis: Long = 5_000L,
    onHang: (Collection<WatchdogTask>) -> Unit = {},
    onStuck: (Collection<WatchdogTask>) -> Unit
): ExecutorService = ExecutorServiceWatchdog(
    this,
    object : WatchdogListener {
        override val hangThresholdMillis: Long = hangThresholdMillis
        override val stuckThresholdMillis: Long = stuckThresholdMillis
        override fun onHang(activeTasks: Collection<WatchdogTask>) = onHang(activeTasks)
        override fun onStuck(stuckTasks: Collection<WatchdogTask>) = onStuck(stuckTasks)
    }
)

fun ScheduledExecutorService.withWatchdog(
    stuckThresholdMillis: Long = 5_000L,
    hangThresholdMillis: Long = 5_000L,
    onHang: (Collection<WatchdogTask>) -> Unit = {},
    onStuck: (Collection<WatchdogTask>) -> Unit
): ScheduledExecutorService = ScheduledExecutorServiceWatchdog(
    this,
    object : WatchdogListener {
        override val hangThresholdMillis: Long = hangThresholdMillis
        override val stuckThresholdMillis: Long = stuckThresholdMillis
        override fun onHang(activeTasks: Collection<WatchdogTask>) = onHang(activeTasks)
        override fun onStuck(stuckTasks: Collection<WatchdogTask>) = onStuck(stuckTasks)
    }
)