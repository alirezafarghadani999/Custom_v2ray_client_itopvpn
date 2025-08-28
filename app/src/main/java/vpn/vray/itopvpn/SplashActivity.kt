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

class SplashActivity : AppCompatActivity() {

    private val VPN_REQUEST_CODE = 100
    private var vpnIntent: Intent? = null


    private lateinit var status_view : TextView
    private lateinit var internet_check_prg : ProgressBar
    private lateinit var check_servers_ping_prg : ProgressBar
    private lateinit var get_servers_prg : ProgressBar
    private lateinit var load_and_ad_prg : ProgressBar
    private val context = this
    private var interstitialAd: InterstitialAd? = null
    private val TAG = "SplashActivity"

    private val pingResults = mutableMapOf<String, Long>()
    private var serverAdapter: ServerAdapter? = null

    private var selectedServer: config? = null

    object RetrofitInstance {
        private val retrofit by lazy {
            Retrofit.Builder()
                .baseUrl("http://192.168.1.230:10809/") // your base URL
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        }

        val api: GetConfig by lazy {
            retrofit.create(GetConfig::class.java)
        }
    }


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
            // Initialize the Google Mobile Ads SDK on a background thread.
            MobileAds.initialize(this@SplashActivity) {
                LoadingFullScreenAd()
            }
        }



        lifecycleScope.launch {
            status_view.setText("Checking Internet Connection")
            delay(1000)

            var Splash_servers = GetSplashServers()
            if (!Splash_servers.isNullOrEmpty()) {
                testAllServersAndConnect(Splash_servers!!)
            }


            internet_check_prg.animateTo(50)
            if (!check_internet(context)) exitProcess(0)
            delay(1000)
            internet_check_prg.animateTo(100)
            status_view.setText("Checking Servers")
            delay(1000)
            check_servers_ping_prg.animateTo(50)
            if(!testDownloadFile("https://freetestdata.com/wp-content/uploads/2021/09/500kb.png")) exitProcess(0)
            delay(1000)
            check_servers_ping_prg.animateTo(100)

            status_view.setText("Getting Servers")
            val PublicConfig = GetAppServers()
            delay(2000)
            get_servers_prg.animateTo(50)
            delay(1000)
            get_servers_prg.animateTo(100)

            status_view.setText("Entering ...")
            delay(1000)
            load_and_ad_prg.animateTo(50)
            delay(1000)
            load_and_ad_prg.animateTo(100)


            if (interstitialAd != null){
                interstitialAd?.fullScreenContentCallback =
                    object : FullScreenContentCallback() {
                        override fun onAdDismissedFullScreenContent() {
                            // Called when fullscreen content is dismissed.
                            Log.d(TAG, "Ad was dismissed.")
                            // Don't forget to set the ad reference to null so you
                            // don't show the ad a second time.
                            interstitialAd = null
                            val intent = Intent(this@SplashActivity, MainActivity::class.java)
                            intent.putParcelableArrayListExtra("SERVER_LIST_KEY", ArrayList(PublicConfig))
                            startActivity(intent)
                            stopVpn()
                            finish()
                        }

                        override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                            // Called when fullscreen content failed to show.
                            Log.d(TAG, "Ad failed to show.")
                            // Don't forget to set the ad reference to null so you
                            // don't show the ad a second time.
                            interstitialAd = null
                            val intent = Intent(this@SplashActivity, MainActivity::class.java)
                            intent.putParcelableArrayListExtra("SERVER_LIST_KEY", ArrayList(PublicConfig))
                            startActivity(intent)
                            stopVpn()
                            finish()

                        }

                        override fun onAdShowedFullScreenContent() {
                            // Called when fullscreen content is shown.
                            Log.d(TAG, "Ad showed fullscreen content.")
                        }

                        override fun onAdImpression() {
                            // Called when an impression is recorded for an ad.
                            Log.d(TAG, "Ad recorded an impression.")
                        }

                        override fun onAdClicked() {
                            // Called when ad is clicked.
                            Log.d(TAG, "Ad was clicked.")
                        }
                    }

                interstitialAd?.show(context)

            }else{
                val intent = Intent(this@SplashActivity, MainActivity::class.java)
                intent.putParcelableArrayListExtra("SERVER_LIST_KEY", ArrayList(PublicConfig))
                startActivity(intent)
                stopVpn()
                finish()
            }
        }

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

            val bestServerConfigKey = pingResults
                .filter { it.value > 0 }
                .minByOrNull { it.value }?.key

            if (bestServerConfigKey != null) {
                selectedServer = servers.find { it.config == bestServerConfigKey }
                Log.d(
                    "splash",
                    "Best server selected automatically: ${selectedServer?.name} with ping: ${pingResults[bestServerConfigKey]}"
                )
            } else {
                selectedServer = servers.firstOrNull()
                Log.d(
                    "splash",
                    "No server with a valid ping found. Selecting first server as default."
                )
            }

            withContext(Dispatchers.Main) {
                requestVpnPermission()
            }

        }
    }

    fun check_internet(context: Context) : Boolean{
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (connectivityManager != null) {
            val capabilities =
                connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            if (capabilities != null) {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    Log.i("Internet", "NetworkCapabilities.TRANSPORT_CELLULAR")
                    return true
                } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    Log.i("Internet", "NetworkCapabilities.TRANSPORT_WIFI")
                    return true
                } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    Log.i("Internet", "NetworkCapabilities.TRANSPORT_ETHERNET")
                    return true
                }
            }
        }
        return false
    }

    suspend fun testDownloadFile(url: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.requestMethod = "GET"
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    return@withContext false
                }

                val inputStream = connection.inputStream
                val downloadedData = inputStream.readBytes()
                inputStream.close()
                connection.disconnect()

                // بررسی اندازه فایل (50KB = 51200 بایت)
                return@withContext downloadedData.size >= 51200
            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext false
            }
        }
    }


    fun ProgressBar.animateTo(targetProgress: Int, durationMillis: Long = 500) {
        ValueAnimator.ofInt(this.progress, targetProgress).apply {
            duration = durationMillis
            addUpdateListener {
                this@animateTo.progress = it.animatedValue as Int
            }
            start()
        }
    }


    fun CoroutineScope.LoadingFullScreenAd(){
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
            },
        )
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

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping VPN service", e)
            Toast.makeText(this, "خطا در قطع VPN", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun GetAppServers(): List<config> {
        return try {
            // اگه همه چیز اوکی باشه، لیست از اینجا برمی‌گرده
            val configList = RetrofitInstance.api.GetAppConfig()
            configList // این همون return هست

        } catch (e: IOException) {
            // این خطا وقتی اتفاق میفته که مشکل شبکه باشه (مثلاً اینترنت قطع)
            Log.e("NetworkError", "IOException: No internet connection? ${e.message}")
            emptyList() // یه لیست خالی برگردون که اپ کرش نکنه

        } catch (e: HttpException) {
            // این خطا برای ارورهای HTTP هست (مثل 404, 500, 403)
            Log.e("NetworkError", "HttpException: Server error code: ${e.code()}, ${e.message()}")
            emptyList() // باز هم یه لیست خالی برگردون
        }
    }

    private suspend fun GetSplashServers(): List<config> {
        return try {
            // اگه همه چیز اوکی باشه، لیست از اینجا برمی‌گرده
            val configList = RetrofitInstance.api.GetSplashConfig()
            configList // این همون return هست

        } catch (e: IOException) {
            // این خطا وقتی اتفاق میفته که مشکل شبکه باشه (مثلاً اینترنت قطع)
            Log.e("NetworkError", "IOException: No internet connection? ${e.message}")
            emptyList() // یه لیست خالی برگردون که اپ کرش نکنه

        } catch (e: HttpException) {
            // این خطا برای ارورهای HTTP هست (مثل 404, 500, 403)
            Log.e("NetworkError", "HttpException: Server error code: ${e.code()}, ${e.message()}")
            emptyList() // باز هم یه لیست خالی برگردون
        }
    }

    private fun getV2rayConfig(): String {
        var config = V2rayManager.convertVlessUriToJsonOptimal(selectedServer?.config)
        println(config)
        return config
    }


}


