package com.suenara.executorservicewatchdog

interface WatchdogListener {
    val hangThresholdMillis: Long
    val stuckThresholdMillis: Long

    fun onHang(activeTasks: Collection<WatchdogTask>) = Unit
    fun onStuck(stuckTasks: Collection<WatchdogTask>) = Unit
}