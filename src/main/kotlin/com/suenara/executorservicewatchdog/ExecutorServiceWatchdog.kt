package com.suenara.executorservicewatchdog

import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

open class ExecutorServiceWatchdog(
    private val executorService: ExecutorService,
    private val hangThresholdMillis: Long = 5_000L,
    watchdogThreadProvider: (Runnable) -> Unit = DEFAULT_THREAD_PROVIDER
) : ExecutorService {

    var hangListener: ((Collection<WatchdogTask>) -> Unit)? = null
    val activeTasks: Collection<WatchdogTask>
        get() = tasks.values.toList()

    @Volatile
    private var isReleased = false

    private val taskNumber = AtomicLong(0)
    private val tasks = ConcurrentHashMap<Long, WatchdogTask>()
    private val lock = ReentrantLock()
    private val condition = lock.newCondition()

    init {
        watchdogThreadProvider {
            val watchdogTaskExecuted = AtomicBoolean(true)
            while (!executorService.isTerminated && !isReleased) {
                if (watchdogTaskExecuted.compareAndSet(true, false)) {
                    execute { watchdogTaskExecuted.set(true) }
                }
                lock.withLock {
                    var timeToWait = TimeUnit.MILLISECONDS.toNanos(hangThresholdMillis)
                    val endTime = time() + timeToWait
                    while (time() < endTime) {
                        try {
                            condition.awaitNanos(timeToWait)
                        } catch (_: InterruptedException) {
                            timeToWait = endTime - time() - 1
                        }
                    }
                    if (!tasks.isEmpty() && !isReleased) {
                        hangListener?.invoke(tasks.values.toList())
                    }
                }
            }
        }
    }

    fun release() {
        isReleased = true
        lock.withLock {
            condition.signal()
        }
    }

    override fun execute(command: Runnable) {
        val num = rememberTask()
        executorService.execute(wrap(command, num))
    }

    override fun shutdown() {
        executorService.shutdown()
        release()
    }

    override fun shutdownNow(): MutableList<Runnable> {
        val unsubmitted =
            executorService.shutdownNow().mapTo(arrayListOf()) { if (it is WrappedRunnable<*>) it.origin else it }
        release()
        return unsubmitted
    }

    override fun isShutdown(): Boolean {
        return executorService.isShutdown
    }

    override fun isTerminated(): Boolean {
        return executorService.isTerminated
    }

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean {
        return executorService.awaitTermination(timeout, unit)
    }

    override fun <T : Any?> submit(task: Callable<T>): Future<T> {
        val num = rememberTask()
        return executorService.submit(wrap(task, num))
    }

    override fun <T : Any?> submit(task: Runnable, result: T): Future<T> {
        val num = rememberTask()
        return executorService.submit(wrap(task, num), result)
    }

    override fun submit(task: Runnable): Future<*> {
        val num = rememberTask()
        return executorService.submit(wrap(task, num))
    }

    override fun <T : Any?> invokeAll(tasks: MutableCollection<out Callable<T>>): MutableList<Future<T>> {
        val remeberedTasks = tasks.map {
            val num = rememberTask()
            wrap(it, num)
        }
        return executorService.invokeAll(remeberedTasks)
    }

    override fun <T : Any?> invokeAll(
        tasks: MutableCollection<out Callable<T>>,
        timeout: Long,
        unit: TimeUnit
    ): MutableList<Future<T>> {
        val remeberedTasks = tasks.map {
            val num = rememberTask()
            wrap(it, num)
        }
        return executorService.invokeAll(remeberedTasks, timeout, unit)
    }

    override fun <T : Any?> invokeAny(tasks: MutableCollection<out Callable<T>>): T {
        val remeberedTasks = tasks.map {
            val num = rememberTask()
            wrap(it, num)
        }
        return executorService.invokeAny(remeberedTasks)
    }

    override fun <T : Any?> invokeAny(tasks: MutableCollection<out Callable<T>>, timeout: Long, unit: TimeUnit): T {
        val remeberedTasks = tasks.map {
            val num = rememberTask()
            wrap(it, num)
        }
        return executorService.invokeAny(remeberedTasks, timeout, unit)
    }

    protected fun rememberTask(task: WatchdogTask = createTask()): Long {
        val num = taskNumber.getAndIncrement()
        tasks[num] = task.copy(startTime = time())
        return num
    }

    protected fun createTask(): WatchdogTask = Thread.currentThread().run {
        WatchdogTask(submitThread = name, submitTime = time(), stacktrace = stackTrace)
    }

    protected fun completeTask(task: Long) {
        tasks.remove(task)
        lock.withLock {
            condition.signal()
        }
    }

    private fun wrap(runnable: Runnable, taskToRemove: Long): WrappedRunnable<Long> = WrappedRunnable(runnable) {
        completeTask(taskToRemove)
    }

    private fun <T> wrap(callable: Callable<T>, taskToRemove: Long): WrappedCallable<T, Long> =
        WrappedCallable(callable) {
            completeTask(taskToRemove)
        }

    private fun time(): Long = System.nanoTime()

    protected class WrappedRunnable<V>(
        val origin: Runnable,
        private val beforeRun: (() -> V?)? = null,
        private val afterRun: ((V?) -> Unit)? = null
    ) : Runnable {
        override fun run() {
            val before = beforeRun?.invoke()
            origin.run()
            afterRun?.invoke(before)
        }
    }

    protected class WrappedCallable<T, V>(
        val origin: Callable<T>,
        private val beforeRun: (() -> V)? = null,
        private val afterRun: ((V?) -> Unit)? = null
    ) : Callable<T> {
        override fun call(): T {
            val before = beforeRun?.invoke()
            val result = origin.call()
            afterRun?.invoke(before)
            return result
        }
    }

    companion object {
        @JvmStatic
        protected val DEFAULT_THREAD_PROVIDER: (Runnable) -> Unit = {
            thread(start = true, isDaemon = true, name = "service-watchdog") { it.run() }
        }
    }
}