package vpn.vray.itopvpn

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import vpn.vray.itopvpn.ApiConnector.GetConfig
import vpn.vray.itopvpn.ApiConnector.objects.config
import vpn.vray.itopvpn.service.MyVpnService
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.system.exitProcess

/**
 * The application's splash screen, displayed on startup.
 *
 * This activity is responsible for performing initial setup tasks, such as:
 * - Checking for internet connectivity.
 * - Fetching server configurations from the API.
 * - Testing server latencies to find an optimal server.
 * - Pre-loading an interstitial ad.
 * - Automatically connecting to the best available server.
 * After these tasks are complete, it transitions to the [MainActivity].
 */
class SplashActivity : AppCompatActivity() {

    private val VPN_REQUEST_CODE = 100
    private var vpnIntent: Intent? = null

    private lateinit var status_view: TextView
    private lateinit var internet_check_prg: ProgressBar
    private lateinit var check_servers_ping_prg: ProgressBar
    private lateinit var get_servers_prg: ProgressBar
    private lateinit var load_and_ad_prg: ProgressBar
    private val context = this
    private var interstitialAd: InterstitialAd? = null
    private val TAG = "SplashActivity"

    private val pingResults = mutableMapOf<String, Long>()
    private var serverAdapter: ServerAdapter? = null
    private var selectedServer: config? = null

    /**
     * A singleton object for managing the Retrofit instance used for API calls.
     */
    object RetrofitInstance {
        private val retrofit by lazy {
            Retrofit.Builder()
                .baseUrl("http://192.168.1.230:10809/") // Base URL for the API
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        }

        /** The Retrofit API service instance. */
        val api: GetConfig by lazy {
            retrofit.create(GetConfig::class.java)
        }
    }

    /**
     * Initializes the activity, its views, and starts the splash screen sequence.
     *
     * @param savedInstanceState If the activity is being re-initialized, this Bundle contains
     * the most recent data supplied in [onSaveInstanceState].
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.splash_activity)

        status_view = findViewById(R.id.splash_status)
        internet_check_prg = findViewById(R.id.internet_connectio_check)
        check_servers_ping_prg = findViewById(R.id.check_servers_ping)
        get_servers_prg = findViewById(R.id.get_servers)
        load_and_ad_prg = findViewById(R.id.load_and_ad)
        vpnIntent = Intent(this, MyVpnService::class.java)

        CoroutineScope(Dispatchers.IO).launch {
            MobileAds.initialize(this@SplashActivity) {}
            LoadingFullScreenAd()
        }

        lifecycleScope.launch {
            status_view.text = "Checking Internet Connection"
            delay(1000)

            val splashServers = GetSplashServers()
            if (!splashServers.isNullOrEmpty()) {
                testAllServersAndConnect(splashServers)
            }

            internet_check_prg.animateTo(50)
            if (!check_internet(context)) exitProcess(0)
            delay(1000)
            internet_check_prg.animateTo(100)
            status_view.text = "Checking Servers"
            delay(1000)
            check_servers_ping_prg.animateTo(50)
            if (!testDownloadFile("https://freetestdata.com/wp-content/uploads/2021/09/500kb.png")) exitProcess(0)
            delay(1000)
            check_servers_ping_prg.animateTo(100)

            status_view.text = "Getting Servers"
            val publicConfig = GetAppServers()
            delay(2000)
            get_servers_prg.animateTo(50)
            delay(1000)
            get_servers_prg.animateTo(100)

            status_view.text = "Entering ..."
            delay(1000)
            load_and_ad_prg.animateTo(50)
            delay(1000)
            load_and_ad_prg.animateTo(100)

            if (interstitialAd != null) {
                interstitialAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
                    override fun onAdDismissedFullScreenContent() {
                        interstitialAd = null
                        navigateToMain(publicConfig)
                    }

                    override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                        interstitialAd = null
                        navigateToMain(publicConfig)
                    }

                    override fun onAdShowedFullScreenContent() {}
                    override fun onAdImpression() {}
                    override fun onAdClicked() {}
                }
                interstitialAd?.show(context)
            } else {
                navigateToMain(publicConfig)
            }
        }
    }

    private fun navigateToMain(config: List<config>) {
        val intent = Intent(this@SplashActivity, MainActivity::class.java)
        intent.putParcelableArrayListExtra("SERVER_LIST_KEY", ArrayList(config))
        startActivity(intent)
        stopVpn()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }

    private fun testAllServersAndConnect(servers: List<config>) {
        lifecycleScope.launch {
            val jobs = servers.mapIndexed { index, server ->
                launch(Dispatchers.IO) {
                    val configJson = V2rayManager.convertVlessUriToJsonOptimal(server.config)
                    val delay = if (configJson != null) V2rayManager.pingConfig(configJson) else -2L
                    pingResults[server.config] = delay
                    Log.e("delay", "Server: ${server.name}, Delay: $delay")
                    withContext(Dispatchers.Main) {
                        serverAdapter?.notifyItemChanged(index)
                    }
                }
            }
            jobs.joinAll()

            val bestServerConfigKey = pingResults.filter { it.value > 0 }.minByOrNull { it.value }?.key
            selectedServer = if (bestServerConfigKey != null) {
                servers.find { it.config == bestServerConfigKey }
            } else {
                servers.firstOrNull()
            }
            withContext(Dispatchers.Main) {
                requestVpnPermission()
            }
        }
    }

    /**
     * Checks for an active internet connection.
     * @param context The application context.
     * @return `true` if an internet connection is available, `false` otherwise.
     */
    fun check_internet(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        return capabilities != null && (
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        )
    }

    /**
     * Tests the network speed by downloading a sample file.
     * @param url The URL of the file to download.
     * @return `true` if the file is downloaded successfully and meets the size criteria, `false` otherwise.
     */
    suspend fun testDownloadFile(url: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.requestMethod = "GET"
                connection.connect()
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext false
                val inputStream = connection.inputStream
                val downloadedData = inputStream.readBytes()
                inputStream.close()
                connection.disconnect()
                downloadedData.size >= 51200 // Check if file size is at least 50KB
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    /**
     * Animates the progress of a [ProgressBar] to a target value.
     * @param targetProgress The target progress value.
     * @param durationMillis The duration of the animation in milliseconds.
     */
    fun ProgressBar.animateTo(targetProgress: Int, durationMillis: Long = 500) {
        ValueAnimator.ofInt(this.progress, targetProgress).apply {
            duration = durationMillis
            addUpdateListener { this@animateTo.progress = it.animatedValue as Int }
            start()
        }
    }

    /**
     * Loads a full-screen interstitial ad.
     */
    fun CoroutineScope.LoadingFullScreenAd() {
        InterstitialAd.load(
            this@SplashActivity,
            getString(R.string.AD_UNIT_ID),
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    Log.d(TAG, "Ad was loaded.")
                    interstitialAd = ad
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    Log.d(TAG, adError.message)
                    interstitialAd = null
                    LoadingFullScreenAd()
                }
            }
        )
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            startVpnService()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                startVpnService()
            } else {
                Toast.makeText(this, "VPN permission is required", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startVpnService() {
        try {
            val config = getV2rayConfig()
            vpnIntent?.putExtra("config", config)
            vpnIntent?.action = MyVpnService.ACTION_START_VPN
            startService(vpnIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN service", e)
            Toast.makeText(this, "Error starting VPN", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopVpn() {
        try {
            val stopIntent = Intent(this, MyVpnService::class.java)
            stopIntent.action = MyVpnService.ACTION_STOP_VPN
            startService(stopIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping VPN service", e)
        }
    }

    private suspend fun GetAppServers(): List<config> {
        return try {
            RetrofitInstance.api.GetAppConfig()
        } catch (e: IOException) {
            Log.e("NetworkError", "IOException: ${e.message}")
            emptyList()
        } catch (e: HttpException) {
            Log.e("NetworkError", "HttpException: ${e.code()}, ${e.message()}")
            emptyList()
        }
    }

    private suspend fun GetSplashServers(): List<config> {
        return try {
            RetrofitInstance.api.GetSplashConfig()
        } catch (e: IOException) {
            Log.e("NetworkError", "IOException: ${e.message}")
            emptyList()
        } catch (e: HttpException) {
            Log.e("NetworkError", "HttpException: ${e.code()}, ${e.message()}")
            emptyList()
        }
    }

    private fun getV2rayConfig(): String {
        return V2rayManager.convertVlessUriToJsonOptimal(selectedServer?.config)
    }
}


