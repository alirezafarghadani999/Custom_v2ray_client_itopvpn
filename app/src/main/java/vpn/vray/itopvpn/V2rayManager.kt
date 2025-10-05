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

/**
 * A singleton object for managing the V2Ray core lifecycle and operations.
 *
 * This object is responsible for initializing, starting, and stopping the V2Ray core,
 * handling geo asset files, converting VLESS URIs to JSON configuration, and
 * performing ping tests.
 */
object V2rayManager {

    private var controller: CoreController? = null
    private var isRunning = false
    private const val GEO_ASSETS_DIR = "geo"

    /**
     * Initializes the V2Ray core environment.
     * This must be called once before any other operations.
     *
     * @param context The application context, used to access file storage and assets.
     */
    fun init(context: Context) {
        val corePath = context.filesDir.absolutePath
        Libv2ray.initCoreEnv(corePath, "")
        controller = Libv2ray.newCoreController(SimpleCallbackHandler())
        copyAssetsIfNeeded(context)
    }

    /**
     * Starts the V2Ray core with a given JSON configuration.
     *
     * @param config The V2Ray configuration in JSON format.
     * @return `true` if the core started successfully, `false` otherwise.
     */
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

    /**
     * Stops the V2Ray core.
     */
    fun stop() {
        try {
            controller?.stopLoop()
            isRunning = false
            Log.d("V2rayManager", "V2Ray stopped")
        } catch (e: Exception) {
            Log.e("V2rayManager", "Failed to stop V2Ray: ${e.message}", e)
        }
    }

    /**
     * Checks if the V2Ray core is currently running.
     *
     * @return `true` if the core is running, `false` otherwise.
     */
    fun isRunning(): Boolean = isRunning

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
            return true
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
            if (destinationFile.parentFile?.exists() == false) {
                destinationFile.parentFile?.mkdirs()
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

    /**
     * Measures the delay (ping) of a given V2Ray configuration.
     *
     * @param config The V2Ray configuration in JSON format.
     * @param url The URL to test the delay against. Defaults to "https://www.google.com".
     * @return The latency in milliseconds, or -1L on failure.
     */
    suspend fun pingConfig(config: String, url: String = "https://www.google.com"): Long {
        return withContext(Dispatchers.IO) {
            try {
                Libv2ray.measureOutboundDelay(config, url)
            } catch (e: Exception) {
                Log.e("V2rayManager", "Ping test failed: ${e.message}", e)
                -1L
            }
        }
    }

    /**
     * Converts a VLESS URI into a full V2Ray JSON configuration.
     *
     * @param vlessUri The VLESS configuration URI.
     * @return A formatted JSON string representing the full configuration, or an empty string on failure.
     */
    fun convertVlessUriToJsonOptimal(vlessUri: String?): String {
        try {
            if (vlessUri.isNullOrBlank()) return ""
            val uri = Uri.parse(vlessUri)
            if (uri.scheme != "vless") return ""

            val uuid = uri.userInfo ?: return ""
            val address = uri.host ?: return ""
            val port = uri.port.takeIf { it > 0 } ?: return ""
            val remarks = uri.fragment?.let { URLDecoder.decode(it, "UTF-8") } ?: "VLESS Config"

            val params = uri.queryParameterNames.associateWith { uri.getQueryParameter(it) ?: "" }
            val network = params["type"] ?: "tcp"
            val security = params["security"] ?: "none"
            val encryption = params["encryption"] ?: "none"
            val flow = params["flow"] ?: ""

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

            val streamSettings = JSONObject().apply {
                put("network", network)
                put("security", security)
                when (network) {
                    "ws" -> put("wsSettings", JSONObject().apply {
                        put("path", params["path"] ?: "/")
                        put("headers", JSONObject().put("Host", params["host"] ?: address))
                    })
                    "grpc" -> put("grpcSettings", JSONObject().apply {
                        put("serviceName", params["serviceName"] ?: "")
                        put("multiMode", params["mode"] == "multi")
                    })
                    "tcp" -> params["headerType"]?.takeIf { it != "none" }?.let {
                        put("tcpSettings", JSONObject().put("header", JSONObject().put("type", it)))
                    }
                }
                when (security) {
                    "tls" -> put("tlsSettings", JSONObject().apply {
                        put("serverName", params["sni"] ?: address)
                        put("allowInsecure", params["allowInsecure"]?.toBooleanStrictOrNull() ?: false)
                        params["fp"]?.let { put("fingerprint", it) }
                        params["alpn"]?.let { put("alpn", JSONArray(it.split(","))) }
                    })
                    "reality" -> put("realitySettings", JSONObject().apply {
                        put("serverName", params["sni"] ?: address)
                        put("publicKey", params["pbk"] ?: "")
                        params["fp"]?.let { put("fingerprint", it) }
                        params["sid"]?.let { put("shortId", it) }
                    })
                }
            }

            val proxyOutbound = JSONObject().apply {
                put("tag", "proxy")
                put("protocol", "vless")
                put("settings", JSONObject().put("vnext", JSONArray().put(vnextObject)))
                put("streamSettings", streamSettings)
            }

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

    /**
     * A simple implementation of [CoreCallbackHandler] to receive logs and status
     * updates from the V2Ray core.
     */
    class SimpleCallbackHandler : CoreCallbackHandler {
        override fun onEmitStatus(p0: Long, p1: String?): Long {
            p1?.let { Log.d("V2rayCallback", "[$p0] $it") }
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