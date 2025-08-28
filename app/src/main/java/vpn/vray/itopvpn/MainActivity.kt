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

class MainActivity : AppCompatActivity() , ServerListDialogFragment.ServerSelectListener{

    companion object {
        private const val VPN_REQUEST_CODE = 100
        private const val TAG = "MainActivity"
    }

    private var vpnIntent: Intent? = null
    private lateinit var receiver: BroadcastReceiver
    private var isConnecting = false
    private var isDisConnecting = false

    private var receivedServerList: ArrayList<config>? = null
    private val pingResults = mutableMapOf<String, Long>()
    private var serverAdapter: ServerAdapter? = null // برای آپدیت کردن لیست

    private var selectedServer: config? = null

    private var trafficLogJob: Job? = null

    @SuppressLint("MissingInflatedId", "NewApi")
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        V2rayManager.init(this)
        setContentView(R.layout.activity_main)

        receivedServerList = intent.getParcelableArrayListExtra("SERVER_LIST_KEY", config::class.java)
        val serverSelector = findViewById<FrameLayout>(R.id.servers)

        // 1. به محض دریافت لیست، تست پینگ‌ها را شروع کن
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
                    Toast.makeText(this, "سرور قابل استفاده‌ای یافت نشد", Toast.LENGTH_SHORT).show()
                }

            } else {
                Toast.makeText(this, "سروری وجود ندارد", Toast.LENGTH_SHORT).show()
            }
        }


        // تنظیم BroadcastReceiver
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
                        ping in 300..499 -> Color.rgb(255, 165, 0) // نارنجی
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

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    override fun onResume() {
        super.onResume()
        updateButtonStates()
    }

    private fun testAllServers(servers: List<config>) {
        lifecycleScope.launch {
            // یک "job" برای هر تست پینگ ایجاد می‌کنیم
            val jobs = servers.mapIndexed { index, server ->
                launch(Dispatchers.IO) {
                    val configJson = V2rayManager.convertVlessUriToJsonOptimal(server.config)
                    val delay = if (configJson != null) {
                        V2rayManager.pingConfig(configJson)
                    } else {
                        -2L // کد خطا برای کانفیگ نامعتبر
                    }
                    pingResults[server.config] = delay
                    Log.e("delay", "Server: ${server.name}, Delay: $delay")

                    // به آداپتور خبر میدیم که این آیتم آپدیت شده
                    withContext(Dispatchers.Main) {
                        serverAdapter?.notifyItemChanged(index)
                    }
                }
            }
            jobs.joinAll() // منتظر می‌مانیم تا تمام تست‌های پینگ تمام شوند

            // حالا که همه پینگ‌ها گرفته شده، بهترین سرور را پیدا می‌کنیم
            val bestServerConfigKey = pingResults
                .filter { it.value > 0 } // فقط پینگ‌های موفق (بزرگتر از صفر) را در نظر بگیر
                .minByOrNull { it.value }?.key // کلیدی (کانفیگ) که کمترین مقدار (پینگ) را دارد

            if (bestServerConfigKey != null) {
                // سرور متناظر با بهترین کانفیگ را از لیست اصلی پیدا کن
                selectedServer = servers.find { it.config == bestServerConfigKey }
                Log.d(TAG, "Best server selected automatically: ${selectedServer?.name} with ping: ${pingResults[bestServerConfigKey]}")
            } else {
                // اگر هیچ سروری پینگ موفق نداشت، اولین سرور لیست را به عنوان پیش‌فرض انتخاب کن
                selectedServer = servers.firstOrNull()
                Log.d(TAG, "No server with a valid ping found. Selecting first server as default.")
            }

            withContext(Dispatchers.Main){
                findViewById<TextView>(R.id.server_name).setText(selectedServer?.name)
            }
        }
    }

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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                Log.d(TAG, "VPN permission granted")
                startVpnService()
            } else {
                Log.e(TAG, "VPN permission denied")
                Toast.makeText(this, "برای استفاده از VPN باید اجازه دهید", Toast.LENGTH_LONG).show()
            }
        }
    }

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
            Toast.makeText(this, "خطا در شروع VPN", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopVpn() {
        try {

            Log.d(TAG, "Stopping VPN service")
            val stopIntent = Intent(this, MyVpnService::class.java)
            stopIntent.action = MyVpnService.ACTION_STOP_VPN
            startService(stopIntent)
            isDisConnecting = true
            updateButtonStates()

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping VPN service", e)
            Toast.makeText(this, "خطا در قطع VPN", Toast.LENGTH_SHORT).show()
        }
    }


    private fun updateButtonStates() {
        val Cbtn: FrameLayout = findViewById(R.id.connection_btn)
        val Cimg: ImageView = findViewById(R.id.moshak)
        val Cload : ProgressBar = findViewById(R.id.status_loading)

        val statusView: TextView = findViewById(R.id.status)
        val pingView: TextView = findViewById(R.id.ping)

        val uploads: TextView = findViewById(R.id.upload_speed)
        val downloads: TextView = findViewById(R.id.download_speed)
        val speed_zero = "0.00 KB/s"

        val shapeDrawable = ContextCompat.getDrawable(this, R.drawable.connect_btn) as GradientDrawable
        var color: Int? = null
        if (MyVpnService.isRunning()) {
            color = Color.parseColor("#5cbf72")
            isConnecting = false
            Cimg.setImageResource(R.drawable.moshak_connection_green)
            statusView.text = "Connected"

            lifecycleScope.launch(Dispatchers.IO) { // عملیات شبکه رو در ترد IO انجام بده
                val ping = V2rayManager.pingConfig(V2rayManager.convertVlessUriToJsonOptimal(selectedServer?.config))

                // حالا برای آپدیت UI برگرد به ترد اصلی
                withContext(Dispatchers.Main) {
                    pingView.text = ping.toString()

                    val pingColor = when {
                        ping < 300 -> Color.GREEN
                        ping in 300..499 -> Color.rgb(255, 165, 0) // نارنجی
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
                        delay(20000) // 10 ثانیه صبر کن

                        val currentRxBytes = TrafficStats.getUidRxBytes(applicationInfo.uid)
                        val currentTxBytes = TrafficStats.getUidTxBytes(applicationInfo.uid)

                        // محاسبه سرعت در 10 ثانیه اخیر (بایت بر ثانیه)
                        val downloadSpeedBps = (currentRxBytes - lastRxBytes) / 10.0
                        val uploadSpeedBps = (currentTxBytes - lastTxBytes) / 10.0
                        withContext(Dispatchers.Main) {
                            uploads.setText("${"%.2f".format(downloadSpeedBps / 1024)} KB/s")
                            downloads.setText("${"%.2f".format(uploadSpeedBps / 1024)} KB/s")
                        }
                        lastRxBytes = currentRxBytes
                        lastTxBytes = currentTxBytes
                    }
                }
            }
        } else {
            color = Color.parseColor("#f35148")
            isDisConnecting = false
            Cimg.setImageResource(R.drawable.moshak_connection)
            statusView.text = "Not Connected"
            pingView.text = "-"
            pingView.setTextColor(color)

            trafficLogJob?.cancel()
            trafficLogJob = null
            uploads.setText(speed_zero)
            downloads.setText(speed_zero)

        }
        if (isConnecting || isDisConnecting){
            Cload.setVisibility(View.VISIBLE)
            color = Color.parseColor("#fdd928")
            Cimg.setImageResource(R.drawable.moshak_connection_yellow)
            statusView.text = "..."
            pingView.text = "~"
            pingView.setTextColor(color)


        }else{
            Cload.setVisibility(View.INVISIBLE)
        }

        shapeDrawable.setStroke(4, color)
        Cbtn.background = shapeDrawable
        statusView.setTextColor(color)

    }


    private fun getV2rayConfig(): String {
        // اگر سروری انتخاب شده بود از اون استفاده کن، وگرنه از کانفیگ هاردکد شده به عنوان پشتیبان استفاده کن
        val configToUse = selectedServer?.config

        return V2rayManager.convertVlessUriToJsonOptimal(configToUse)
    }

    override fun onServerSelected(server: config) {
        this.selectedServer = server
        findViewById<TextView>(R.id.server_name).setText(server?.name)

    }
}