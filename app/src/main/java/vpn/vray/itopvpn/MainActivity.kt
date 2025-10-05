package vpn.vray.itopvpn

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vpn.vray.itopvpn.ApiConnector.objects.config
import vpn.vray.itopvpn.service.MyVpnService

/**
 * The main activity of the application, responsible for the primary user interface,
 * managing the VPN connection lifecycle, and handling user interactions.
 *
 * This activity displays the connection status, server selection, and traffic information.
 * It communicates with [MyVpnService] to start and stop the VPN connection.
 */
class MainActivity : AppCompatActivity(), ServerListDialogFragment.ServerSelectListener {

    companion object {
        private const val VPN_REQUEST_CODE = 100
        private const val TAG = "MainActivity"
    }

    private var vpnIntent: Intent? = null
    private lateinit var receiver: BroadcastReceiver
    private var isConnecting = false
    private var isDisconnecting = false

    private var receivedServerList: ArrayList<config>? = null
    private val pingResults = mutableMapOf<String, Long>()
    private var serverAdapter: ServerAdapter? = null
    private var selectedServer: config? = null
    private var trafficLogJob: Job? = null

    /**
     * Initializes the activity, sets up the UI, and prepares the VPN service.
     *
     * This method is called when the activity is first created. It sets up listeners
     * for the connection button and server selector, registers a [BroadcastReceiver]
     * to receive state updates from the VPN service, and starts the process of
     * pinging servers to find the optimal one.
     *
     * @param savedInstanceState If the activity is being re-initialized, this Bundle
     * contains the most recent data supplied in [onSaveInstanceState].
     */
    @SuppressLint("MissingInflatedId", "NewApi")
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        V2rayManager.init(this)
        setContentView(R.layout.activity_main)

        receivedServerList = intent.getParcelableArrayListExtra("SERVER_LIST_KEY", config::class.java)
        val serverSelector = findViewById<FrameLayout>(R.id.servers)

        if (!receivedServerList.isNullOrEmpty()) {
            testAllServers(receivedServerList!!)
        }
        serverSelector.setOnClickListener {
            if (!receivedServerList.isNullOrEmpty()) {
                val filteredList = receivedServerList!!.filter { server ->
                    pingResults[server.config] != -1L
                } as ArrayList<config>

                if (filteredList.isNotEmpty()) {
                    val dialog = ServerListDialogFragment.newInstance(filteredList, pingResults)
                    dialog.listener = this
                    dialog.onAdapterCreated = { createdAdapter ->
                        this.serverAdapter = createdAdapter
                    }
                    dialog.show(supportFragmentManager, "ServerListDialog")
                } else {
                    Toast.makeText(this, "No usable server found", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "No server available", Toast.LENGTH_SHORT).show()
            }
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "UPDATE_BUTTON_STATES_ACTION") {
                    updateButtonStates()
                }
            }
        }
        val filter = IntentFilter("UPDATE_BUTTON_STATES_ACTION")
        registerReceiver(receiver, filter, RECEIVER_EXPORTED)

        vpnIntent = Intent(this, MyVpnService::class.java)

        val Cbtn: FrameLayout = findViewById(R.id.connection_btn)
        val pingView: TextView = findViewById(R.id.ping)

        pingView.setOnClickListener {
            if (MyVpnService.isRunning()) {
                lifecycleScope.launch {
                    val ping = V2rayManager.pingConfig(V2rayManager.convertVlessUriToJsonOptimal(selectedServer?.config))
                    pingView.text = ping.toString()
                    val pingColor = when {
                        ping in 0..300 -> Color.GREEN
                        ping in 300..499 -> Color.rgb(255, 165, 0) // Orange
                        else -> Color.RED
                    }
                    pingView.setTextColor(pingColor)
                }
            }
        }

        Cbtn.setOnClickListener {
            if (MyVpnService.isRunning()) {
                stopVpn()
            } else {
                requestVpnPermission()
            }
        }
        updateButtonStates()
    }

    /**
     * Cleans up resources when the activity is destroyed.
     * Unregisters the [BroadcastReceiver].
     */
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    /**
     * Refreshes the UI when the activity is resumed.
     * Calls [updateButtonStates] to ensure the UI reflects the current connection state.
     */
    override fun onResume() {
        super.onResume()
        updateButtonStates()
    }

    /**
     * Asynchronously tests the ping of all available servers.
     *
     * This function launches a coroutine for each server to measure its latency.
     * After all pings are complete, it automatically selects the server with the
     * lowest positive ping time as the `selectedServer`.
     *
     * @param servers The list of [config] objects to be tested.
     */
    private fun testAllServers(servers: List<config>) {
        lifecycleScope.launch {
            val jobs = servers.mapIndexed { index, server ->
                launch(Dispatchers.IO) {
                    val configJson = V2rayManager.convertVlessUriToJsonOptimal(server.config)
                    val delay = if (configJson != null) {
                        V2rayManager.pingConfig(configJson)
                    } else {
                        -2L // Error code for invalid config
                    }
                    pingResults[server.config] = delay
                    Log.e("delay", "Server: ${server.name}, Delay: $delay")
                    withContext(Dispatchers.Main) {
                        serverAdapter?.notifyItemChanged(index)
                    }
                }
            }
            jobs.joinAll()

            val bestServerConfigKey = pingResults
                .filter { it.value > 0 }
                .minByOrNull { it.value }?.key

            if (bestServerConfigKey != null) {
                selectedServer = servers.find { it.config == bestServerConfigKey }
                Log.d(TAG, "Best server selected automatically: ${selectedServer?.name} with ping: ${pingResults[bestServerConfigKey]}")
            } else {
                selectedServer = servers.firstOrNull()
                Log.d(TAG, "No server with a valid ping found. Selecting first server as default.")
            }
            withContext(Dispatchers.Main) {
                findViewById<TextView>(R.id.server_name).setText(selectedServer?.name)
            }
        }
    }

    /**
     * Requests permission from the user to establish a VPN connection.
     * If permission is already granted, it proceeds to start the VPN service.
     */
    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            Log.d(TAG, "Requesting VPN permission")
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            Log.d(TAG, "VPN permission already granted")
            startVpnService()
        }
    }

    /**
     * Handles the result from the VPN permission request dialog.
     *
     * @param requestCode The integer request code originally supplied to startActivityForResult().
     * @param resultCode The integer result code returned by the child activity.
     * @param data An Intent, which can return result data to the caller.
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                Log.d(TAG, "VPN permission granted")
                startVpnService()
            } else {
                Log.e(TAG, "VPN permission denied")
                Toast.makeText(this, "Permission is required to use the VPN", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Starts the VPN service with the selected server configuration.
     * It sends an intent to [MyVpnService] to initiate the connection.
     */
    private fun startVpnService() {
        try {
            val config = getV2rayConfig()
            vpnIntent?.putExtra("config", config)
            vpnIntent?.action = MyVpnService.ACTION_START_VPN
            Log.d(TAG, "Starting VPN service")
            startService(vpnIntent)
            isConnecting = true
            updateButtonStates()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN service", e)
            Toast.makeText(this, "Error starting VPN", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Stops the VPN service.
     * It sends an intent to [MyVpnService] to terminate the connection.
     */
    private fun stopVpn() {
        try {
            Log.d(TAG, "Stopping VPN service")
            val stopIntent = Intent(this, MyVpnService::class.java)
            stopIntent.action = MyVpnService.ACTION_STOP_VPN
            startService(stopIntent)
            isDisconnecting = true
            updateButtonStates()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping VPN service", e)
            Toast.makeText(this, "Error stopping VPN", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Updates the UI elements to reflect the current state of the VPN connection.
     * This includes changing colors, text, and visibility of components like
     * the progress bar and connection status indicators.
     */
    private fun updateButtonStates() {
        val Cbtn: FrameLayout = findViewById(R.id.connection_btn)
        val Cimg: ImageView = findViewById(R.id.moshak)
        val Cload: ProgressBar = findViewById(R.id.status_loading)
        val statusView: TextView = findViewById(R.id.status)
        val pingView: TextView = findViewById(R.id.ping)
        val uploads: TextView = findViewById(R.id.upload_speed)
        val downloads: TextView = findViewById(R.id.download_speed)
        val speed_zero = "0.00 KB/s"

        val shapeDrawable = ContextCompat.getDrawable(this, R.drawable.connect_btn) as GradientDrawable
        var color: Int?
        if (MyVpnService.isRunning()) {
            color = Color.parseColor("#5cbf72") // Green
            isConnecting = false
            Cimg.setImageResource(R.drawable.moshak_connection_green)
            statusView.text = "Connected"

            lifecycleScope.launch(Dispatchers.IO) {
                val ping = V2rayManager.pingConfig(V2rayManager.convertVlessUriToJsonOptimal(selectedServer?.config))
                withContext(Dispatchers.Main) {
                    pingView.text = ping.toString()
                    val pingColor = when {
                        ping < 300 -> Color.GREEN
                        ping in 300..499 -> Color.rgb(255, 165, 0) // Orange
                        else -> Color.RED
                    }
                    pingView.setTextColor(pingColor)
                }
            }

            if (trafficLogJob?.isActive != true) {
                trafficLogJob = lifecycleScope.launch(Dispatchers.IO) {
                    var lastRxBytes = TrafficStats.getUidRxBytes(applicationInfo.uid)
                    var lastTxBytes = TrafficStats.getUidTxBytes(applicationInfo.uid)
                    while (isActive) {
                        delay(10000) // 10 seconds
                        val currentRxBytes = TrafficStats.getUidRxBytes(applicationInfo.uid)
                        val currentTxBytes = TrafficStats.getUidTxBytes(applicationInfo.uid)
                        val downloadSpeedBps = (currentRxBytes - lastRxBytes) / 10.0
                        val uploadSpeedBps = (currentTxBytes - lastTxBytes) / 10.0
                        withContext(Dispatchers.Main) {
                            uploads.text = "${"%.2f".format(downloadSpeedBps / 1024)} KB/s"
                            downloads.text = "${"%.2f".format(uploadSpeedBps / 1024)} KB/s"
                        }
                        lastRxBytes = currentRxBytes
                        lastTxBytes = currentTxBytes
                    }
                }
            }
        } else {
            color = Color.parseColor("#f35148") // Red
            isDisconnecting = false
            Cimg.setImageResource(R.drawable.moshak_connection)
            statusView.text = "Not Connected"
            pingView.text = "-"
            pingView.setTextColor(color)
            trafficLogJob?.cancel()
            trafficLogJob = null
            uploads.text = speed_zero
            downloads.text = speed_zero
        }

        if (isConnecting || isDisconnecting) {
            Cload.visibility = View.VISIBLE
            color = Color.parseColor("#fdd928") // Yellow
            Cimg.setImageResource(R.drawable.moshak_connection_yellow)
            statusView.text = "..."
            pingView.text = "~"
            pingView.setTextColor(color)
        } else {
            Cload.visibility = View.INVISIBLE
        }

        shapeDrawable.setStroke(4, color)
        Cbtn.background = shapeDrawable
        statusView.setTextColor(color)
    }

    /**
     * Retrieves the V2Ray configuration JSON for the currently selected server.
     * @return A JSON string representing the V2Ray configuration.
     */
    private fun getV2rayConfig(): String {
        val configToUse = selectedServer?.config
        return V2rayManager.convertVlessUriToJsonOptimal(configToUse)
    }

    /**
     * Callback method from [ServerListDialogFragment.ServerSelectListener].
     *
     * This method is invoked when the user selects a server from the dialog.
     * It updates the `selectedServer` and the displayed server name.
     *
     * @param server The [config] object of the server that was selected.
     */
    override fun onServerSelected(server: config) {
        this.selectedServer = server
        findViewById<TextView>(R.id.server_name).text = server.name
    }
}