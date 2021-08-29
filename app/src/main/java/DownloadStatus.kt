import com.adamratzman.spotify.models.Track
import kotlinx.coroutines.yield
import java.text.DecimalFormat
import kotlin.math.round
import kotlin.math.roundToInt

class DownloadStatus {

    var progress: Int
        get() = calcProgress()

    private var jobs: MutableMap<Pair<String, SearchQuery>, Float>

    constructor() {
        this.jobs = mutableMapOf()
        this.progress = 0
    }

    fun updateJob(track: Pair<String, SearchQuery>, progress: Float) {
        jobs[track] = progress
    }

    fun getJobs() = sequence {
        jobs.forEach {
            yield(it)
        }
    }

    private fun calcProgress(): Int {
        val total = jobs.keys.count() * 100 //value if all jobs were completed
        val done = jobs.values.sum() //value for the actually completed jobs

        if (total == 0)
            return 0

        else
            return ("%.2f".format(done.toDouble() / total).toDouble() * 100).roundToInt()
    }
}