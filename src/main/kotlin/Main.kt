import com.suenara.executorservicewatchdog.ExecutorServiceWatchdog
import com.suenara.executorservicewatchdog.ScheduledExecutorServiceWatchdog
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

fun main(args: Array<String>) {
    println("Hello World!")

    val executor = ScheduledExecutorServiceWatchdog(Executors.newSingleThreadScheduledExecutor())
    executor.hangListener = { tasks ->
        val time = System.nanoTime()
        println("hanging:\n${tasks.joinToString("\n") { it.toString(time) }}")
    }
    executor.schedule({
        var i = 5
        while (i-- > 0) {
            Thread.sleep(10_000)
        }
    }, 15, TimeUnit.SECONDS)
    while (readLine().orEmpty().isBlank()){
    }
    executor.release()
    while (readLine().orEmpty().isBlank()){
    }
}