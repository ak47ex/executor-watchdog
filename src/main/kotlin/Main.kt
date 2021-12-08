import com.suenara.executorservicewatchdog.withWatchdog
import java.util.concurrent.*
import kotlin.concurrent.thread
import kotlin.random.Random

fun main(args: Array<String>) {
    println("Hello World!")

    val executor = Executors.newSingleThreadExecutor().withWatchdog(
        stuckThresholdMillis = 5000L,
        hangThresholdMillis = 1000L,
        onHang = {
            println("hang!")
        }
    ) {
        println("stuck!")
    }

    test(executor)

    while (readLine().orEmpty().isBlank()){
    }
    executor.shutdown()
    while (readLine().orEmpty().isBlank()){
    }
}

private fun test(executorService: ExecutorService) {
    val threads = 5
    val tasksPerThread = 1
    val minJobDuration = 1_000L
    val maxJobDuration = 10_000L

    val startLatch = CountDownLatch(threads)
    val threadDeadLatch = CountDownLatch(threads)
    val jobLatch = CountDownLatch(tasksPerThread * threads)
    repeat(threads) {
        thread(name = "thread-num-${it + 1}") {
            startLatch.countDown()
            startLatch.await()
            repeat(tasksPerThread) {
                executorService.submit {
                    Thread.sleep(Random.nextLong(minJobDuration, maxJobDuration))
                    jobLatch.countDown()
                }
            }
            threadDeadLatch.countDown()
        }
    }
    threadDeadLatch.await()
    println("everything submitted!")
    jobLatch.await()
    println("everything completed!")
}