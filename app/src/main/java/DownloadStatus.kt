import kotlinx.coroutines.yield
import java.text.DecimalFormat
import kotlin.math.round

class DownloadStatus {

    var progress: Double
        get() = calcProgress()

    private var jobs: MutableMap<String, Float>

    constructor() {
        this.jobs = mutableMapOf<String, Float>()
        this.progress = 0.0
    }

    fun updateJob(track: String, progress: Float) {
        jobs[track] = progress
    }

    fun getJobs() = sequence {
        jobs.forEach {
            yield(it)
        }
    }

    private fun calcProgress(): Double {
        val total = jobs.keys.count() * 100 //value if all jobs were completed
        val done = jobs.values.sum() //value for the actually completed jobs

        if (total == 0)
            return 0.0

        else
            return "%.2f".format(done.toDouble() / total).toDouble() * 100
    }
}