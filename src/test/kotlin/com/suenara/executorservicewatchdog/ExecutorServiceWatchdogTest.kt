package com.suenara.executorservicewatchdog

import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.test.assertFalse

/**
 * Those tests might be flaky due to time dependency. For pure testing, we should provide own test time provider
 * into [ExecutorServiceWatchdog]
 */
internal class ExecutorServiceWatchdogTest {

    @Test
    fun `when single thread is dead`() {
        val ex = obtainSingleThreadExecutor()
        val lock = ReentrantLock()
        val condition = lock.newCondition()
        val wd = ex.withWatchdog(
            hangThresholdMillis = 100,
            onHang = {
                lock.withLock {
                    condition.signal()
                }
            },
            onStuck = {}
        )
        wd.execute {
            lock.withLock {
                condition.awaitUninterruptibly()
            }
        }
    }

    @Test
    fun `when single thread is stuck`() {
        val ex = obtainSingleThreadExecutor()
        val lock = ReentrantLock()
        val condition = lock.newCondition()
        val wd = ex.withWatchdog(
            stuckThresholdMillis = 100,
            onStuck = {
                lock.withLock { condition.signal() }
            }
        )
        wd.execute {
            lock.withLock {
                condition.awaitUninterruptibly()
            }
        }
        assert(true)
    }

    @Test
    fun `when one thread of pool is hang`() {
        val threadNum = 6
        val startLatch = CountDownLatch(threadNum)
        val finishLatch = CountDownLatch(threadNum)
        val ex = obtainMultiThreadExecutor(threadNum)
        val wd = ex.withWatchdog(
            hangThresholdMillis = 100,
            onHang = {
                finishLatch.countDown()
            },
            onStuck = {}
        )
        repeat(threadNum) {
            wd.execute {
                startLatch.countDown()
                startLatch.await()
                if (it > 0) {
                    finishLatch.countDown()
                } else {
                    finishLatch.await()
                    wd.shutdown()
                }
            }
        }
        assert(finishLatch.await(5, TimeUnit.SECONDS))
        assert(wd.awaitTermination(5, TimeUnit.SECONDS))
    }

    @Test
    fun `when thread pool is stuck`() {
        val threadNum = 6
        val startLatch = CountDownLatch(threadNum)
        val finishLatch = CountDownLatch(threadNum)
        val ex = obtainMultiThreadExecutor(threadNum)
        val wd = ex.withWatchdog(stuckThresholdMillis = 100) {
            repeat(threadNum) {
                finishLatch.countDown()
            }
        }
        repeat(threadNum) {
            wd.execute {
                startLatch.countDown()
                startLatch.await()
                finishLatch.await()
                wd.shutdown()
            }
        }
        assert(finishLatch.await(5, TimeUnit.SECONDS))
        assert(wd.awaitTermination(5, TimeUnit.SECONDS))
    }


    @Test
    fun `when thread pool is hang and not stuck`() {
        val threadNum = 2
        val startLatch = CountDownLatch(threadNum)
        val finishLatch = CountDownLatch(threadNum)
        val ex = obtainMultiThreadExecutor(threadNum)
        val stuckSignal = AtomicBoolean(false)
        val wd = ex.withWatchdog(
            stuckThresholdMillis = 100,
            hangThresholdMillis = 500,
            onHang = {
                repeat(it.size) {
                    finishLatch.countDown()
                }
            },
            onStuck = {
                stuckSignal.set(true)
            }
        )
        repeat(threadNum) {
            wd.execute {
                startLatch.countDown()
                startLatch.await()
                if (it == 0) finishLatch.countDown()
                else finishLatch.await()
            }
        }
        assert(finishLatch.await(5, TimeUnit.SECONDS))
        wd.shutdownNow()
        assert(wd.awaitTermination(5, TimeUnit.SECONDS))
        assertFalse(stuckSignal.get())
    }

    @Test
    fun `when thread pool is slow and not stuck`() {
        val taskCount = 6
        val ex = obtainSingleThreadExecutor()
        val stuckSignal = AtomicBoolean(false)
        val lock = ReentrantLock()
        val condition = lock.newCondition()

        val wd = ex.withWatchdog(
            stuckThresholdMillis = 5000,
            hangThresholdMillis = 100,
            onHang = {
                lock.withLock {
                    condition.signal()
                }
            },
            onStuck = {
                stuckSignal.set(true)
            }
        )

        repeat(taskCount) {
            wd.execute {
                lock.withLock {
                    condition.await()
                }
            }
        }
        wd.execute {
            wd.shutdownNow()
        }
        assert(wd.awaitTermination(5, TimeUnit.SECONDS))
        assertFalse(stuckSignal.get())
    }

    private fun obtainSingleThreadExecutor() = Executors.newSingleThreadExecutor()
    private fun obtainMultiThreadExecutor(threadNum: Int) = Executors.newFixedThreadPool(threadNum)
}