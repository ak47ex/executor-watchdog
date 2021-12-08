@file:Suppress("MemberVisibilityCanBePrivate")

package com.suenara.executorservicewatchdog

import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

open class ExecutorServiceWatchdog(
    private val executorService: ExecutorService,
    private val listener: WatchdogListener,
    watchdogThreadProvider: (Runnable) -> Unit = DEFAULT_THREAD_PROVIDER
) : ExecutorService {

    val activeTasks: Collection<WatchdogTask>
        get() = tasks.valuesList()

    @Volatile
    private var isReleased = false

    private val taskNumber = AtomicLong(0)
    private val tasks = ConcurrentHashMap<Long, WatchdogTask>()
    private val lock = ReentrantLock()
    private val condition = lock.newCondition()

    init {
        watchdogThreadProvider {
            runWatchdogLoop()
        }
    }

    fun release() {
        isReleased = true
        lock.withLock {
            condition.signal()
        }
    }

    override fun execute(command: Runnable) {
        val wdTask = createTask()
        executorService.execute(wrap(command, wdTask))
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
        val wdTask = createTask()
        return executorService.submit(wrap(task, wdTask))
    }

    override fun <T : Any?> submit(task: Runnable, result: T): Future<T> {
        val wdTask = createTask()
        return executorService.submit(wrap(task, wdTask), result)
    }

    override fun submit(task: Runnable): Future<*> {
        val wdTask = createTask()
        return executorService.submit(wrap(task, wdTask))
    }

    override fun <T : Any?> invokeAll(tasks: MutableCollection<out Callable<T>>): MutableList<Future<T>> {
        val remeberedTasks = tasks.map {
            val wdTask = createTask()
            wrap(it, wdTask)
        }
        return executorService.invokeAll(remeberedTasks)
    }

    override fun <T : Any?> invokeAll(
        tasks: MutableCollection<out Callable<T>>,
        timeout: Long,
        unit: TimeUnit
    ): MutableList<Future<T>> {
        val remeberedTasks = tasks.map {
            val wdTask = createTask()
            wrap(it, wdTask)
        }
        return executorService.invokeAll(remeberedTasks, timeout, unit)
    }

    override fun <T : Any?> invokeAny(tasks: MutableCollection<out Callable<T>>): T {
        val remeberedTasks = tasks.map {
            val wdTask = createTask()
            wrap(it, wdTask)
        }
        return executorService.invokeAny(remeberedTasks)
    }

    override fun <T : Any?> invokeAny(tasks: MutableCollection<out Callable<T>>, timeout: Long, unit: TimeUnit): T {
        val remeberedTasks = tasks.map {
            val wdTask = createTask()
            wrap(it, wdTask)
        }
        return executorService.invokeAny(remeberedTasks, timeout, unit)
    }

    protected fun rememberTask(task: WatchdogTask = createTask()): Long {
        val num = taskNumber.getAndIncrement()
        lock.withLock {
            tasks[num] = task.copy(startTime = time())
        }
        return num
    }

    protected fun createTask(): WatchdogTask = Thread.currentThread().run {
        WatchdogTask(
            submitThread = name,
            submitTime = time(),
            stacktrace = stackTrace
                .asSequence()
                .filterNot { it.className.startsWith(Thread::class.java.name) }
                .filterNot { it.className.startsWith(ExecutorServiceWatchdog::class.java.name) }
                .filterNot { it.className.startsWith(this@ExecutorServiceWatchdog.javaClass.name) }
                .toList()
        )
    }

    protected fun completeTask(task: Long) {
        lock.withLock {
            tasks.remove(task)
            condition.signal()
        }
    }

    protected fun wrap(runnable: Runnable, task: WatchdogTask): Runnable {
        return WrappedRunnable(runnable, { rememberTask(task) }, { it?.let(::completeTask) })
    }

    protected fun <V> wrap(callable: Callable<V>, task: WatchdogTask): Callable<V> {
        return WrappedCallable(callable, { rememberTask(task) }, { it?.let(::completeTask) })
    }

    private fun time(): Long = System.nanoTime()

    private fun runWatchdogLoop() {
        val hangThreshold = TimeUnit.MILLISECONDS.toNanos(listener.hangThresholdMillis)
        val stuckThreshold = TimeUnit.MILLISECONDS.toNanos(listener.stuckThresholdMillis)
        val stuckHelper = StuckHelper(stuckThreshold)
        outer@while (!isTerminated && !isReleased) {
            stuckHelper.check()
            lock.withLock {
                var timeToWait = minOf(hangThreshold, stuckThreshold)
                val endTime = time() + hangThreshold
                val hasTasks = tasks.isNotEmpty()
                while (hasTasks && time() < endTime) {
                    try {
                        val estimated = condition.awaitNanos(timeToWait)
                        if (estimated > 0) {
                            break
                        } else {
                            val time = time()
                            val overdue = time - endTime
                            val tasksAtAwake = tasks.valuesList()
                            if (overdue > 0 && tasksAtAwake.isNotEmpty() && !isReleased) {
                                tasks.valuesList()
                                    .mapNotNull { task -> task.takeIf { it.getExecutionTimeNs(time) > hangThreshold } }
                                    .takeIf { it.isNotEmpty() }
                                    ?.let {
                                        listener.onHang(it)
                                    }
                            }
                            stuckHelper.check()
                        }
                    } catch (_: InterruptedException) {
                        timeToWait = endTime - time() - 1
                    }
                }
            }
        }
    }


    /**
     * Default implementation of Iterable<T>.toList() with ConcurrentHashMap::values
     * is not applicable in concurrent access and leads to crash.
     * https://youtrack.jetbrains.com/issue/KT-30185
     */
    private fun <V> ConcurrentHashMap<*, V>.valuesList(): List<V> {
        val v = values
        return when (size) {
            0 -> emptyList()
            1 -> {
                val iter = v.iterator()
                if(iter.hasNext()) listOf(iter.next()) else emptyList()
            }
            else -> ArrayList(v)
        }
    }


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

    private inner class StuckHelper(private val stuckThreshold: Long) {
        private val watchdogTaskExecuted = AtomicBoolean(true)
        private var stuckSubmission = time()
        private var stuckDeadline = stuckSubmission + stuckThreshold

        fun check() {
            if (!isTerminated && !isShutdown) {
                if (watchdogTaskExecuted.compareAndSet(true, false)) {
                    stuckSubmission = time()
                    stuckDeadline = stuckSubmission + stuckThreshold
                    try {
                        execute { watchdogTaskExecuted.set(true) }
                    } catch (_: RejectedExecutionException) {
                    }
                } else if (time() > stuckDeadline) {
                    stuckDeadline = Long.MAX_VALUE
                    listener.onStuck(activeTasks)
                }
            }
        }
    }

    companion object {
        @JvmStatic
        protected val DEFAULT_THREAD_PROVIDER: (Runnable) -> Unit = {
            thread(start = true, isDaemon = true, name = "service-watchdog") { it.run() }
        }
    }
}