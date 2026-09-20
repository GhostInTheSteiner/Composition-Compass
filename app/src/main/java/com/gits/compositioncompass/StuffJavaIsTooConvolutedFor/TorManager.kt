package com.gits.compositioncompass.StuffJavaIsTooConvolutedFor

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.freehaven.tor.control.TorControlConnection
import org.torproject.jni.TorService
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

//Manages the app's embedded Tor instance (info.guardianproject:tor-android).
//Tor runs as a foreground service and exposes a SOCKS5 proxy on 127.0.0.1:<socksPort>.
//Only Pandora's tuner-API traffic (see PandoraQuery.callApi) is pointed at this
//proxy; yt-dlp is a native process with its own sockets and always connects
//directly, so no per-process proxying is needed on that side.
//
//The embedded, in-process TorService does NOT broadcast its SOCKS port as an Intent
//extra, so the port is read from the control connection via
//`GETINFO net/listeners/socks` once the service has connected.
//
//IMPORTANT: this tor-android build does NOT pick up <filesDir>/torrc.custom (verified
//via GETCONF: ExitNodes was empty and GeoIPFile pointed at /data/local/tmp/geoip).
//All configuration is therefore applied at runtime over the control connection
//(see applyExitConfig). The SOCKS port is only published once that configuration has
//been applied AND verified, so awaitReady() cannot return before the US-only exit
//restriction is active. That is what makes the "fail closed" behaviour in PandoraQuery
//actually hold.
//
//Usage:
//  TorManager.start(applicationContext)  // once, e.g. from MainActivity.initializeApp()
//  ...later, before any Pandora request...
//  TorManager.awaitReady()               // suspends until tor is configured (or timeout)
object TorManager {

    private const val BOOTSTRAP_TIMEOUT_MS = 120_000L
    private const val CONFIG_RETRIES = 20
    private const val CONFIG_RETRY_DELAY_MS = 500L

    @Volatile
    private var torService: TorService? = null

    //-1 = not usable. Only ever set to a real port after applyExitConfig() succeeded.
    @Volatile
    private var socksPort: Int = -1

    @Volatile
    private var started = false

    @Volatile
    private var appContext: Context? = null

    //Last reason the configuration could not be applied (null = fine / not tried yet).
    //Shown in PandoraQuery's error and in debugConfig() so failures aren't silent.
    @Volatile
    var lastConfigError: String? = null
        private set

    //Own scope so config work isn't tied to any caller's lifecycle;
    //cancelled explicitly in stop().
    @Volatile
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    //onServiceConnected and STATUS_ON can both trigger a refresh; serialize them.
    private val refreshMutex = Mutex()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? TorService.LocalBinder)?.getService() ?: return
            torService = service
            //Tor may already be running by the time we bind (no further STATUS_ON
            //will arrive), or still starting (STATUS_ON will trigger another refresh,
            //which is a no-op if this one already succeeded).
            scope.launch { refreshSocksPortWithRetry() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            torService = null
            socksPort = -1
        }
    }

    //TorService broadcasts its state transitions. Used only to know *when* to
    //(re)configure and when to invalidate a stale port.
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != TorService.ACTION_STATUS) return

            when (intent.getStringExtra(TorService.EXTRA_STATUS)) {
                TorService.STATUS_ON ->
                    scope.launch { refreshSocksPortWithRetry() }

                //Tor is going down or is down: the port AND the runtime config are gone.
                //Clearing this forces a re-apply on the next start, and keeps callers
                //failing closed in the meantime.
                TorService.STATUS_STOPPING, TorService.STATUS_OFF ->
                    socksPort = -1
            }
        }
    }

    // ─── Startup / configuration ────────────────────────────────────────────

    fun start(context: Context) {
        if (started) return
        started = true

        val appContext = context.applicationContext
        this.appContext = appContext
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        //torrc.custom is not read by this tor-android build; remove the stale file
        //from earlier versions so nobody assumes it is doing something.
        runCatching { File(appContext.filesDir, "torrc.custom").delete() }

        appContext.bindService(
            Intent(appContext, TorService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )

        ContextCompat.registerReceiver(
            appContext,
            statusReceiver,
            IntentFilter(TorService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun stop(context: Context) {
        if (!started) return
        started = false
        socksPort = -1
        torService = null
        scope.cancel()

        runCatching { context.applicationContext.unregisterReceiver(statusReceiver) }
        runCatching { context.applicationContext.unbindService(serviceConnection) }
    }

    private suspend fun refreshSocksPortWithRetry() {
        refreshMutex.withLock { refreshLocked() }
    }

    private suspend fun refreshLocked() {
        if (socksPort >= 0) return //already configured for this tor instance

        val ctx = appContext
        if (ctx == null) {
            lastConfigError = "TorManager.start() was not called"
            return
        }

        //Copy the GeoIP databases out of assets (idempotent). Done here, on the IO
        //scope, so the ~25 MB copy never blocks the main thread.
        copyAssetIfMissing(ctx, "geoip")
        copyAssetIfMissing(ctx, "geoip6")

        repeat(CONFIG_RETRIES) {
            val port = querySocksPort()
            val conn = currentControlConnection()

            when {
                port == null -> lastConfigError = "could not read SOCKS port from tor yet"
                conn == null -> lastConfigError = "no control connection yet"
                applyExitConfig(ctx, conn) -> {
                    socksPort = port //publish ONLY after the config is verified
                    return
                }
                //else: applyExitConfig already set lastConfigError
            }

            delay(CONFIG_RETRY_DELAY_MS)
        }

        //Do not guess a port. awaitReady() will time out and callers fail closed.
        socksPort = -1
    }

    //Loads the GeoIP databases, PROVES they loaded, and only then restricts exits to
    //the US. Order matters: {us} matches nothing without GeoIP data.
    private fun applyExitConfig(ctx: Context, conn: TorControlConnection): Boolean {
        return try {
            val geoip = File(ctx.filesDir, "geoip")
            val geoip6 = File(ctx.filesDir, "geoip6")

            if (!geoip.exists() || geoip.length() == 0L) {
                lastConfigError = "geoip missing in filesDir (asset not shipped or copy failed)"
                return false
            }

            conn.setConf("GeoIPFile", geoip.absolutePath)
            if (geoip6.exists() && geoip6.length() > 0L)
                conn.setConf("GeoIPv6File", geoip6.absolutePath)

            //Throws "GeoIP data not loaded" if the database did not load.
            val country = conn.getInfo("ip-to-country/8.8.8.8")
            if (country != "us") {
                lastConfigError = "GeoIP loaded but 8.8.8.8 resolved to '$country'"
                return false
            }

            conn.setConf("ExitNodes", "{us}")
            conn.setConf("StrictNodes", "1")

            //Mark existing circuits as dirty so new streams get circuits that
            //satisfy the new exit restriction.
            conn.signal("NEWNYM")

            lastConfigError = null
            true
        } catch (e: Exception) {
            lastConfigError = "${e.javaClass.simpleName}: ${e.message}"
            false
        }
    }

    private fun copyAssetIfMissing(context: Context, name: String) {
        val target = File(context.filesDir, name)
        if (target.exists() && target.length() > 0L) return

        //Copy to a temp file and rename, so an interrupted copy never leaves a
        //truncated file behind that would be treated as valid on the next launch.
        val tmp = File(context.filesDir, "$name.tmp")
        runCatching {
            context.assets.open(name).use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            if (!tmp.renameTo(target)) error("rename failed")
        }.onFailure {
            tmp.delete()
            lastConfigError = "copying asset '$name' failed: ${it.message}"
        }
    }

    // ─── Control connection helpers ─────────────────────────────────────────

    private fun currentControlConnection(): TorControlConnection? =
        runCatching { torService?.getTorControlConnection() }.getOrNull()

    //Asks the live control connection which port tor's SOCKS listener actually bound to.
    private fun querySocksPort(): Int? {
        val conn = currentControlConnection() ?: return null

        //Typical response: "127.0.0.1:9050" (quoted, possibly space-separated
        //if tor has multiple SOCKS listeners configured).
        val raw = runCatching { conn.getInfo("net/listeners/socks") }.getOrNull() ?: return null

        return raw
            .replace("\"", "")
            .split(" ")
            .firstOrNull { it.isNotBlank() }
            ?.substringAfterLast(":")
            ?.toIntOrNull()
    }

    // ─── Public API ─────────────────────────────────────────────────────────

    //Blocks the calling coroutine until tor is configured (US exits enforced) and
    //accepting SOCKS connections, or the timeout elapses. Returns false if that never
    //happened - callers are expected to fail closed (never fall back to a direct
    //connection), otherwise routing Pandora outside Tor defeats the purpose.
    suspend fun awaitReady(timeoutMs: Long = BOOTSTRAP_TIMEOUT_MS): Boolean {
        val start = System.currentTimeMillis()

        while (socksPort < 0 && System.currentTimeMillis() - start < timeoutMs)
            delay(250)

        return socksPort >= 0
    }

    //The SOCKS port tor is listening on, or -1 if tor isn't ready.
    //PandoraQuery uses this (after awaitReady()) to build its per-connection SOCKS proxy.
    fun socksPort(): Int = socksPort

    // ─── Debugging ──────────────────────────────────────────────────────────

    //Compares a request through Tor with a direct one, and reports the exit country.
    //Expected: via Tor -> IsTor:true, exit country US; direct -> IsTor:false, your real IP.
    suspend fun checkTor(): String = withContext(Dispatchers.IO) {
        val port = socksPort

        fun fetch(url: String, proxy: Proxy): String {
            val conn = URL(url).openConnection(proxy) as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Connection", "close")
            return try {
                conn.inputStream.bufferedReader().readText().trim()
            } finally {
                conn.disconnect()
            }
        }

        fun attempt(url: String, proxy: Proxy): String =
            runCatching { fetch(url, proxy) }.getOrElse { "FAILED: ${it.message}" }

        val torProxy =
            if (port >= 0) Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
            else null

        val viaTor = torProxy?.let { attempt("https://check.torproject.org/api/ip", it) }
            ?: "n/a (tor not ready, port=$port)"
        val exitCountry = torProxy?.let { attempt("https://ipinfo.io/country", it) }
            ?: "n/a"
        val direct = attempt("https://check.torproject.org/api/ip", Proxy.NO_PROXY)

        "port=$port\n" +
                "via Tor: $viaTor\n" +
                "exit country (ipinfo): $exitCountry\n" +
                "direct:  $direct"
    }

    //Dumps everything needed to tell whether the runtime config took effect.
    suspend fun debugConfig(): String = withContext(Dispatchers.IO) {
        val conn = currentControlConnection()
            ?: return@withContext "no control connection (torService=${torService != null})"

        fun info(key: String): String =
            runCatching { conn.getInfo(key) }.getOrElse { "ERR ${it.message}" }

        fun conf(key: String): String = runCatching {
            conn.getConf(key).joinToString("; ") {
                "${it.key}=${it.value ?: "<unset>"}${if (it.is_default) " (default)" else ""}"
            }.ifEmpty { "<empty>" }
        }.getOrElse { "ERR ${it.message}" }

        fun file(name: String): String {
            val dir = appContext?.filesDir ?: return "?"
            val f = File(dir, name)
            return if (f.exists()) "${f.length()} B" else "MISSING"
        }

        fun country(ip: String): String = info("ip-to-country/$ip")

        //Resolves the exit relay of a circuit path to "ip country".
        //circuit-status path looks like: $FP~nick,$FP~nick,$FP~nick
        fun exitInfo(path: String): String {
            val fp = path.substringAfterLast(",").substringBefore("~").removePrefix("$")
            val r = info("ns/id/$fp").lines().firstOrNull { it.startsWith("r ") }
                ?: return "?"
            val ip = r.split(" ").getOrNull(6) ?: return "?"
            return "$ip ${country(ip)}"
        }

        buildString {
            appendLine("== state ==")
            appendLine("socksPort:   $socksPort")
            appendLine("configError: ${lastConfigError ?: "none"}")

            appendLine("== files ==")
            appendLine("geoip:        ${file("geoip")}")
            appendLine("geoip6:       ${file("geoip6")}")
            appendLine("torrc.custom: ${file("torrc.custom")} (unused, should be MISSING)")

            appendLine("== tor ==")
            appendLine("version:   ${info("version")}")
            appendLine("bootstrap: ${info("status/bootstrap-phase")}")
            appendLine("socks:     ${info("net/listeners/socks")}")

            appendLine("== effective config ==")
            appendLine("ExitNodes:   ${conf("ExitNodes")}   (expect {us})")
            appendLine("StrictNodes: ${conf("StrictNodes")}   (expect 1)")
            appendLine("GeoIPFile:   ${conf("GeoIPFile")}")
            appendLine("GeoIPv6File: ${conf("GeoIPv6File")}")

            appendLine("== geoip lookups ==")
            appendLine("8.8.8.8       -> ${country("8.8.8.8")}   (expect us)")
            appendLine("37.114.50.142 -> ${country("37.114.50.142")}   (expect de)")

            appendLine("== circuits (exit ip + country) ==")
            val circuits = info("circuit-status").lines()
                .filter { " BUILT " in it }
                .take(5)

            if (circuits.isEmpty())
                append("no BUILT circuits")
            else
                append(circuits.joinToString("\n") { line ->
                    val parts = line.split(" ")
                    "#${parts.getOrNull(0)} exit: ${exitInfo(parts.getOrNull(2) ?: "")}"
                })
        }
    }
}