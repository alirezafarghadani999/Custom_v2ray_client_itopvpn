package vpn.vray.itopvpn

import android.content.Context
import android.content.res.AssetManager
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.util.zip.CRC32

object V2rayManager {

    private var controller: CoreController? = null
    private var isRunning = false
    private const val GEO_ASSETS_DIR = "geo" // Assuming geo files are in a 'geo' subfolder in assets

    // فراخوانی برای آماده‌سازی اولیه محیط اجرای V2Ray
    fun init(context: Context) {
        val corePath = context.filesDir.absolutePath
        Libv2ray.initCoreEnv(corePath, "")
        controller = Libv2ray.newCoreController(SimpleCallbackHandler()) // ثابت شده: استفاده از متد کارخانه‌ای
        copyAssetsIfNeeded(context) // کپی geoip و geosite فقط در صورت نیاز
    }

    // شروع هسته V2Ray با پیکربندی JSON
    fun start(config: String): Boolean {
        return try {
            controller?.startLoop(config)
            isRunning = true
            Log.d("V2rayManager", "V2Ray started successfully")
            true
        } catch (e: Exception) {
            Log.e("V2rayManager", "Failed to start V2Ray: ${e.message}", e)
            false
        }
    }

    // توقف اجرای هسته
    fun stop() {
        try {
            controller?.stopLoop()
            isRunning = false
            Log.d("V2rayManager", "V2Ray stopped")
        } catch (e: Exception) {
            Log.e("V2rayManager", "Failed to stop V2Ray: ${e.message}", e)
        }
    }

    fun isRunning(): Boolean = isRunning

    // کپی فایل‌های geoip و geosite از assets به فایل‌سیستم فقط اگر وجود نداشته باشند یا تغییر کرده باشند
    private fun copyAssetsIfNeeded(context: Context) {
        val assetManager: AssetManager = context.assets
        val filesDir: File = context.filesDir
        copyAssetsIfNeededRecursive(assetManager, GEO_ASSETS_DIR, filesDir)
    }

    private fun copyAssetsIfNeededRecursive(assetManager: AssetManager, path: String, destinationDir: File) {
        try {
            val assets = assetManager.list(path) ?: return
            val dir = File(destinationDir, path)
            if (!dir.exists()) dir.mkdirs()

            if (assets.isEmpty()) {
                // It's a file, check if needs copying
                val destinationFile = File(destinationDir, path)
                if (shouldCopyAsset(assetManager, path, destinationFile)) {
                    copyFile(assetManager, path, destinationFile)
                }
            } else {
                for (asset in assets) {
                    val subPath = if (path.isEmpty()) asset else "$path/$asset"
                    copyAssetsIfNeededRecursive(assetManager, subPath, destinationDir)
                }
            }
        } catch (e: IOException) {
            Log.e("V2rayManager", "Asset copy failed: ${e.message}", e)
        }
    }

    // چک کردن اینکه آیا فایل نیاز به کپی دارد یا نه (بر اساس چک‌سام CRC32)
    private fun shouldCopyAsset(assetManager: AssetManager, assetPath: String, destinationFile: File): Boolean {
        if (!destinationFile.exists()) return true

        try {
            val assetInputStream: InputStream = assetManager.open(assetPath)
            val assetCrc = calculateCrc(assetInputStream)
            assetInputStream.close()

            val fileInputStream = destinationFile.inputStream()
            val fileCrc = calculateCrc(fileInputStream)
            fileInputStream.close()

            return assetCrc != fileCrc
        } catch (e: IOException) {
            Log.e("V2rayManager", "CRC check failed: ${e.message}", e)
            return true // در صورت خطا، کپی می‌کنیم
        }
    }

    private fun calculateCrc(inputStream: InputStream): Long {
        val crc = CRC32()
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            crc.update(buffer, 0, bytesRead)
        }
        return crc.value
    }

    private fun copyFile(assetManager: AssetManager, assetPath: String, destinationFile: File) {
        try {
            val inputStream: InputStream = assetManager.open(assetPath)
            if (!destinationFile.parentFile.exists()) {
                destinationFile.parentFile.mkdirs()
            }
            val outputStream = FileOutputStream(destinationFile)
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.close()
            Log.d("V2rayManager", "Copied asset: $assetPath")
        } catch (e: IOException) {
            Log.e("V2rayManager", "File copy failed: ${e.message}", e)
        }
    }

    suspend fun pingConfig(config: String, url: String = "https://www.google.com"): Long { // تغییر outboundTag به url بر اساس مستندات کتابخانه
        return withContext(Dispatchers.IO) { // اجرای عملیات در یک ترد پس‌زمینه
            try {
                Libv2ray.measureOutboundDelay(config, url)
            } catch (e: Exception) {
                Log.e("V2rayManager", "Ping test failed: ${e.message}", e)
                -1L // در صورت بروز هرگونه خطا، -1 برگردانده می‌شود
            }
        }
    }

    fun convertVlessUriToJsonOptimal(vlessUri: String?): String {
        try {
            val uri = Uri.parse(vlessUri)

            if (uri.scheme != "vless") return ""

            // 1. استخراج اطلاعات پایه
            val uuid = uri.userInfo ?: return ""
            val address = uri.host ?: return ""
            val port = uri.port.takeIf { it > 0 } ?: return "" // چک کردن پورت معتبر
            val remarks = uri.fragment?.let { URLDecoder.decode(it, "UTF-8") } ?: "VLESS Config"

            // 2. استخراج پارامترهای Query با مدیریت null
            val params = uri.queryParameterNames.associateWith { uri.getQueryParameter(it) ?: "" }

            val network = params["type"] ?: "tcp"
            val security = params["security"] ?: "none"
            val encryption = params["encryption"] ?: "none"
            val flow = params["flow"] ?: ""

            // 3. ساخت آبجکت vnext
            val vnextUser = JSONObject().apply {
                put("id", uuid)
                put("encryption", encryption)
                if (flow.isNotBlank()) put("flow", flow)
            }
            val vnextObject = JSONObject().apply {
                put("address", address)
                put("port", port)
                put("users", JSONArray().put(vnextUser))
            }

            // 4. ساخت دینامیک آبجکت streamSettings
            val streamSettings = JSONObject().apply {
                put("network", network)
                put("security", security)

                // تنظیمات بر اساس نوع شبکه (TCP, WebSocket, gRPC)
                when (network) {
                    "ws" -> {
                        val wsSettings = JSONObject().apply {
                            put("path", params["path"] ?: "/")
                            val host = params["host"] ?: address
                            put("headers", JSONObject().put("Host", host))
                        }
                        put("wsSettings", wsSettings)
                    }
                    "grpc" -> {
                        val grpcSettings = JSONObject().apply {
                            put("serviceName", params["serviceName"] ?: "")
                            put("multiMode", params["mode"] == "multi")
                        }
                        put("grpcSettings", grpcSettings)
                    }
                    "tcp" -> {
                        val headerType = params["headerType"]
                        if (headerType != null && headerType != "none") {
                            put("tcpSettings", JSONObject().put("header", JSONObject().put("type", headerType)))
                        }
                    }
                }

                // تنظیمات بر اساس نوع امنیت (TLS, Reality)
                when (security) {
                    "tls" -> {
                        val tlsSettings = JSONObject().apply {
                            put("serverName", params["sni"] ?: address)
                            put("allowInsecure", params["allowInsecure"]?.toBooleanStrictOrNull() ?: false)
                            params["fp"]?.let { put("fingerprint", it) }
                            params["alpn"]?.let { put("alpn", JSONArray(it.split(","))) }
                        }
                        put("tlsSettings", tlsSettings)
                    }
                    "reality" -> {
                        val realitySettings = JSONObject().apply {
                            put("serverName", params["sni"] ?: address)
                            put("publicKey", params["pbk"] ?: "")
                            params["fp"]?.let { put("fingerprint", it) }
                            params["sid"]?.let { put("shortId", it) }
                        }
                        put("realitySettings", realitySettings)
                    }
                }
            }

            // 5. ساخت آبجکت اصلی Outbound
            val proxyOutbound = JSONObject().apply {
                put("tag", "proxy")
                put("protocol", "vless")
                put("settings", JSONObject().put("vnext", JSONArray().put(vnextObject)))
                put("streamSettings", streamSettings)
            }

            // 6. ساخت ساختار کامل JSON
            val fullConfig = JSONObject().apply {
                put("log", JSONObject().put("loglevel", "warning"))
                put("inbounds", JSONArray().put(JSONObject().apply {
                    put("tag", "socks")
                    put("port", 10808)
                    put("listen", "127.0.0.1")
                    put("protocol", "socks")
                    put("sniffing", JSONObject().put("enabled", true).put("destOverride", JSONArray().put("http").put("tls")))
                    put("settings", JSONObject().put("auth", "noauth").put("udp", true))
                }))
                put("outbounds", JSONArray()
                    .put(proxyOutbound)
                    .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
                    .put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
                )
            }

            return fullConfig.toString(4)

        } catch (e: Exception) {
            Log.e("V2rayManager", "Failed to convert VLESS URI: ${e.message}", e)
            return ""
        }
    }

    // پیاده‌سازی ساده‌ی CallbackHandler برای دریافت لاگ و آمار از V2Ray
    class SimpleCallbackHandler : CoreCallbackHandler {

        override fun onEmitStatus(p0: Long, p1: String?): Long {
            p1?.let { Log.d("V2rayCallback", "[$p0] $it") } // تغییر به Log.d برای لاگ‌های وضعیت
            return 0
        }

        override fun shutdown(): Long {
            Log.d("V2rayCallback", "V2Ray core shutdown successfully")
            return 0

        }

        override fun startup(): Long {
            Log.d("V2rayCallback", "V2Ray core started successfully")
            return 0

        }
    }
}