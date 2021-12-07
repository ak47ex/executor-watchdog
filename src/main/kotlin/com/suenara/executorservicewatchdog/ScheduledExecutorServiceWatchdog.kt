package com.suenara.executorservicewatchdog

import java.util.concurrent.*

class ScheduledExecutorServiceWatchdog(
    private val executorService: ScheduledExecutorService,
    hangThresholdMillis: Long = 5_000L,
    watchdogThreadProvider: (Runnable) -> Unit = DEFAULT_THREAD_PROVIDER
) : ExecutorServiceWatchdog(executorService, hangThresholdMillis, watchdogThreadProvider), ScheduledExecutorService {

    override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
        val task = createTask()
        return executorService.schedule(wrap(command, task), delay, unit)
    }

    override fun <V : Any?> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> {
        val task = createTask()
        return executorService.schedule(wrap(callable, task), delay, unit)
    }

    override fun scheduleAtFixedRate(
        command: Runnable,
        initialDelay: Long,
        period: Long,
        unit: TimeUnit
    ): ScheduledFuture<*> {
        val task = createTask()
        return executorService.scheduleAtFixedRate(wrap(command, task), initialDelay, period, unit)
    }

    override fun scheduleWithFixedDelay(
        command: Runnable,
        initialDelay: Long,
        delay: Long,
        unit: TimeUnit
    ): ScheduledFuture<*> {
        val task = createTask()
        return executorService.scheduleAtFixedRate(wrap(command, task), initialDelay, delay, unit)
    }

    private fun wrap(runnable: Runnable, task: WatchdogTask): Runnable {
        return WrappedRunnable(runnable, { rememberTask(task) }, { it?.let(::completeTask) })
    }

    private fun <V> wrap(callable: Callable<V>, task: WatchdogTask): Callable<V> {
        return WrappedCallable(callable, { rememberTask(task) }, { it?.let(::completeTask) })
    }
}