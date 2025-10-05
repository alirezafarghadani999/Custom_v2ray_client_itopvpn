package vpn.vray.itopvpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import vpn.vray.itopvpn.MainActivity
import vpn.vray.itopvpn.V2rayManager
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [VpnService] implementation that manages the V2Ray VPN connection.
 *
 * This service is responsible for initializing the V2Ray core, setting up the VPN tunnel,
 * managing the connection lifecycle, and handling foreground service notifications.
 * It uses a native JNI library (`hevtunnel_jni`) to interface with the low-level
 * tunneling components.
 */
class MyVpnService : VpnService() {

    companion object {
        init {
            System.loadLibrary("hevtunnel_jni")
        }

        /**
         * Starts the native tunnel with the given configuration.
         * @param configYaml The tunnel configuration in YAML format.
         * @param tunFd The file descriptor for the TUN interface.
         * @return An integer status code.
         */
        @JvmStatic external fun nativeStartTunnel(configYaml: String, tunFd: Int): Int

        /** Stops the native tunnel. */
        @JvmStatic external fun nativeStopTunnel()

        /** Checks if the native tunnel is currently running. */
        @JvmStatic external fun nativeIsRunning(): Boolean

        /** Closes the specified file descriptor. */
        @JvmStatic external fun nativeCloseFd(tunFd: Int)

        private const val TAG = "MyVpnService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "v2ray_vpn_channel"
        const val ACTION_STOP_VPN = "STOP_VPN"
        const val ACTION_START_VPN = "START_VPN"

        @Volatile private var serviceInstance: MyVpnService? = null

        /**
         * Stops the VPN service from an external component.
         */
        fun stopVpnFromExternal() { serviceInstance?.stopVpnService() }

        /**
         * Checks if the VPN service is currently running.
         * @return `true` if the service is active, `false` otherwise.
         */
        fun isRunning(): Boolean = serviceInstance?.isServiceRunning?.get() == true

        /**
         * Performs a network ping to a specified URL to check connectivity.
         * @param url The URL to ping. Defaults to "https://www.google.com".
         * @param timeoutMs The connection timeout in milliseconds. Defaults to 3000.
         * @return The latency in milliseconds if successful, or -1 on failure.
         */
        suspend fun ping(
            url: String = "https://www.google.com",
            timeoutMs: Int = 3000
        ): Int = withContext(Dispatchers.IO) {
            try {
                val start = System.currentTimeMillis()
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                }
                conn.connect()
                val code = conn.responseCode
                if (code == 200) (System.currentTimeMillis() - start).toInt() else -1
            } catch (e: Exception) {
                Log.e(TAG, "Ping exception: ${e.message}", e)
                -1
            }
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunnelJob: Job? = null
    private var notificationManager: NotificationManager? = null
    private val isServiceRunning = AtomicBoolean(false)
    private val isStopInProgress = AtomicBoolean(false)

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var tunDetachedFd: Int = -1

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_STOP_VPN -> {
                stopVpnService()
                return START_NOT_STICKY
            }
            ACTION_START_VPN -> {
                if (isServiceRunning.get()) return START_NOT_STICKY
                val configJson = intent.getStringExtra("config")
                if (configJson.isNullOrEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, createNotification())
                serviceScope.launch { safeStart(configJson) }
                return START_NOT_STICKY
            }
            else -> {
                if (!isServiceRunning.get()) {
                    val configJson = intent?.getStringExtra("config")
                    if (configJson.isNullOrEmpty()) {
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    startForeground(NOTIFICATION_ID, createNotification())
                    serviceScope.launch { safeStart(configJson) }
                }
                return START_NOT_STICKY
            }
        }
    }

    private suspend fun safeStart(configJson: String) {
        if (!isServiceRunning.compareAndSet(false, true)) return
        try {
            if (!initializeV2Ray(configJson)) throw IllegalStateException("V2Ray failed to start")
            if (!setupVpnTunnel()) throw IllegalStateException("Failed to setup tunnel")
            startWatchdog()
        } catch (t: Throwable) {
            isServiceRunning.set(false)
            try { stopForegroundCompat() } catch (_: Throwable) {}
            stopSelf()
        }
    }

    private var watchdogJob: Job? = null

    /**
     * Checks if a VPN connection is currently active on the device.
     * @param context The application context.
     * @return `true` if a VPN is active, `false` otherwise.
     */
    fun isVpnActive(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networks = connectivityManager.allNetworks
        for (network in networks) {
            val caps = connectivityManager.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                return true
            }
        }
        return false
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            while (isActive) {
                delay(2000)
                if (!isVpnActive(this@MyVpnService)) {
                    Log.w(TAG, "Watchdog: native core stopped unexpectedly, killing service")
                    forceKillVpnService()
                    break
                }
            }
        }
    }

    private fun forceKillVpnService() {
        try {
            Log.w(TAG, "Force killing VPN service")
            nativeStopTunnel()
            if (tunDetachedFd >= 0) {
                try { nativeCloseFd(tunDetachedFd) } catch (_: Throwable) {}
                tunDetachedFd = -1
            }
            tunnelJob?.cancel()
            tunnelJob = null
            try { vpnInterface?.close() } catch (_: Throwable) {}
            vpnInterface = null
            V2rayManager.stop()
            stopForegroundCompat()
            notificationManager?.cancel(NOTIFICATION_ID)
            isServiceRunning.set(false)
            isStopInProgress.set(false)
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(0)
        } catch (e: Throwable) {
            Log.e(TAG, "forceKillVpnService failed: ${e.message}", e)
        }
    }

    /**
     * Stops the VPN service gracefully.
     * This method is idempotent and ensures that the stop process is not initiated multiple times.
     */
    fun stopVpnService() {
        if (!isStopInProgress.compareAndSet(false, true)) return
        if (!isServiceRunning.compareAndSet(true, false)) {
            isStopInProgress.set(false)
            return
        }
        serviceScope.launch {
            try {
                nativeStopTunnel()
                try {
                    if (tunDetachedFd >= 0) {
                        nativeCloseFd(tunDetachedFd)
                    }
                } catch (_: Throwable) { }
                tunDetachedFd = -1
                withTimeoutOrNull(5_000) {
                    tunnelJob?.cancelAndJoin()
                }
                tunnelJob = null
                V2rayManager.stop()
                stopForegroundCompat()
                notificationManager?.cancel(NOTIFICATION_ID)
                delay(500)
                closeTunInterface()
                val intent = Intent("UPDATE_BUTTON_STATES_ACTION")
                sendBroadcast(intent)
                stopSelf()
            } catch (e: Throwable) {
                Log.e(TAG, "VPN stop error: ${e.message}", e)
            } finally {
                isStopInProgress.set(false)
            }
        }
    }

    private suspend fun closeTunInterface() = withContext(Dispatchers.IO) {
        vpnInterface?.let { pfd ->
            try { pfd.close() } catch (_: IOException) {}
        }
        vpnInterface = null
    }

    override fun onDestroy() {
        stopVpnService()
        serviceJob.cancel()
        vpnInterface?.close()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpnService()
        serviceJob.cancel()
        vpnInterface?.close()
        super.onRevoke()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun initializeV2Ray(configJson: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            V2rayManager.init(this@MyVpnService)
            V2rayManager.start(configJson)
        } catch (_: Throwable) { false }
    }

    private suspend fun setupVpnTunnel(): Boolean = withContext(Dispatchers.IO) {
        try {
            val builder = Builder()
            val mtu = 1500
            val netIPv4Address = "10.0.0.2"
            builder.setSession("V2Ray VPN")
                .setMtu(mtu)
                .setBlocking(false)
                .addAddress(netIPv4Address, 32)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
                .addRoute("0.0.0.0", 0)
            try { builder.addDisallowedApplication(packageName) } catch (_: Throwable) {}
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
            vpnInterface = builder.establish() ?: return@withContext false
            val dupPfd = ParcelFileDescriptor.dup(vpnInterface!!.fileDescriptor)
            tunDetachedFd = dupPfd.detachFd()
            val configYaml = buildHevConfigYaml(
                mtu, netIPv4Address, "127.0.0.1", 10808, true
            )
            tunnelJob?.cancel()
            tunnelJob = serviceScope.launch {
                nativeStartTunnel(configYaml, tunDetachedFd)
                val intent = Intent("UPDATE_BUTTON_STATES_ACTION")
                sendBroadcast(intent)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID, "V2Ray VPN Service", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "VPN service notification"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        notificationManager?.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val contentPi = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, MyVpnService::class.java).apply { action = ACTION_STOP_VPN }
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("V2Ray VPN is active")
            .setContentText("Your connection is secure and encrypted")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(contentPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", stopPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun buildHevConfigYaml(
        mtu: Int,
        ipv4: String,
        socksHost: String,
        socksPort: Int,
        useUdp: Boolean,
        username: String? = null,
        password: String? = null
    ): String {
        val udpMode = if (useUdp) "udp" else "tcp"
        val auth = buildString {
            if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
                appendLine("  username: \"$username\"")
                appendLine("  password: \"$password\"")
            }
        }.trimEnd()
        return """
            tunnel:
              name: tun0
              mtu: $mtu
              ipv4: $ipv4
            socks5:
              address: $socksHost
              port: $socksPort
              udp: "$udpMode"
            ${if (auth.isNotEmpty()) auth else ""}
            misc:
              log-level: info
        """.trimIndent()
    }
}
