import com.adamratzman.spotify.models.Track
import com.adamratzman.spotify.utils.Language
import kotlinx.coroutines.yield
import java.text.DecimalFormat
import kotlin.math.round

class DownloadStatus {

    var progress: Double
        get() = calcProgress()

    private var jobs: MutableMap<Pair<String, Track>, Float>

    constructor() {
        this.jobs = mutableMapOf()
        this.progress = 0.0
    }

    fun updateJob(track: Pair<String, Track>, progress: Float) {
        jobs[track] = progress
    }

    fun getJobs() = sequence {
        jobs.forEach {
            yield(Language.it)
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