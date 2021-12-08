import com.suenara.executorservicewatchdog.withWatchdog
import java.util.concurrent.*
import kotlin.concurrent.thread
import kotlin.random.Random

fun main(args: Array<String>) {
    log("Hello World!")

    val executor = Executors.newSingleThreadExecutor().withWatchdog(
        stuckThresholdMillis = 5000L,
        hangThresholdMillis = 1000L,
        onHang = {
            log("hang!")
        },
        onStuck = {
            log("stuck!")
        }
    )

    test(executor)

    while (readLine().orEmpty().isBlank()){
    }
    executor.shutdown()
    while (readLine().orEmpty().isBlank()){
    }
}

private fun test(executorService: ExecutorService) {
    val threads = 8
    val tasksPerThread = 20
    val minJobDuration = 500L
    val maxJobDuration = 10000L

    val startLatch = CountDownLatch(threads)
    val threadDeadLatch = CountDownLatch(threads)
    val jobLatch = CountDownLatch(tasksPerThread * threads)
    repeat(threads) {
        thread(name = "thread-num-${it + 1}") {
            startLatch.countDown()
            startLatch.await()
            repeat(tasksPerThread) {
                executorService.submit {
                    val sleep = Random.nextLong(minJobDuration, maxJobDuration)
                    log("sleep for $sleep")
                    Thread.sleep(sleep)
                    jobLatch.countDown()
                }
            }
            threadDeadLatch.countDown()
        }
    }
    threadDeadLatch.await()
    log("everything submitted!")
    jobLatch.await()
    log("everything completed!")
}

private fun log(text: String) {
    println(text)
}