// app/src/main/cpp/hevtunnel_jni.cpp
#include <jni.h>
#include <thread>
#include <atomic>
#include <string>
#include <string.h>
#include <dlfcn.h>
#include <android/log.h>
// اگر به dup/close نیاز داشتید: #include <unistd.h>

#define LOG_TAG "hevtunnel_jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// نوع تابع‌هایی که از هسته می‌خواهیم
typedef int  (*fn_main_from_str_t)(const unsigned char* cfg, unsigned int len, int tun_fd);
typedef void (*fn_quit_t)(void);

// هندل dlopen و اشاره‌گرهای توابع
static void*               g_handle        = nullptr;
static fn_main_from_str_t  p_main_from_str = nullptr;
static fn_quit_t           p_quit          = nullptr;

// اجرای بک‌گراند
static std::thread         g_worker;
static std::atomic<bool>   g_running{false};

static int g_tun_fd_dup = -1;


static bool ensure_loaded() {
    if (p_main_from_str && p_quit) return true;

    // اسم فایل .so دقیقاً همان چیزی است که در jniLibs دارید
    // مثال: app/src/main/jniLibs/arm64-v8a/libhev-socks5-tunnel.so
    g_handle = dlopen("libhev-socks5-tunnel.so", RTLD_NOW | RTLD_LOCAL);
    if (!g_handle) {
        LOGE("dlopen failed: %s", dlerror());
        return false;
    }

    p_main_from_str = (fn_main_from_str_t) dlsym(g_handle, "hev_socks5_tunnel_main_from_str");
    p_quit          = (fn_quit_t)          dlsym(g_handle, "hev_socks5_tunnel_quit");

    if (!p_main_from_str || !p_quit) {
        LOGE("dlsym failed: main=%p quit=%p (err=%s)", p_main_from_str, p_quit, dlerror());
        return false;
    }

    LOGD("symbols resolved ok");
    return true;
}

static void run_tunnel(const char* conf, int tun_fd) {
    g_running = true;
    if (!ensure_loaded()) { g_running = false; return; }

    // Make a private copy the native side owns
    g_tun_fd_dup = dup(tun_fd);

    int rc = p_main_from_str(
            reinterpret_cast<const unsigned char*>(conf),
            static_cast<unsigned int>(strlen(conf)),
            g_tun_fd_dup
    );

    // Library returned; close our copy if still open
    if (g_tun_fd_dup >= 0) {
        close(g_tun_fd_dup);
        g_tun_fd_dup = -1;
    }
    g_running = false;
}

extern "C"
JNIEXPORT jint JNICALL
Java_vpn_vray_itopvpn_service_MyVpnService_nativeStartTunnel(
        JNIEnv* env, jclass /*clazz*/, jstring config_yaml, jint tun_fd) {
    if (g_running) {
        LOGD("tunnel already running; skip start");
        return 0;
    }

    const char* conf = env->GetStringUTFChars(config_yaml, nullptr);
    std::string conf_copy(conf ? conf : "");
    env->ReleaseStringUTFChars(config_yaml, conf);

    g_worker = std::thread([conf_copy, tun_fd]() {
        run_tunnel(conf_copy.c_str(), (int)tun_fd);
    });
    return 0;
}



extern "C"
JNIEXPORT void JNICALL
Java_vpn_vray_itopvpn_service_MyVpnService_nativeStopTunnel(
        JNIEnv* /*env*/, jclass /*clazz*/) {
    if (!ensure_loaded()) {
        LOGE("stop requested but symbols not loaded");
        return;
    }
    // Ask library to quit
    p_quit();

    // Wait for worker to leave hev_socks5_tunnel_main_from_str
    if (g_worker.joinable()) g_worker.join();

    // Safety: close dup’d fd if still open
    if (g_tun_fd_dup >= 0) {
        close(g_tun_fd_dup);
        g_tun_fd_dup = -1;
    }

    // Optionally release the .so (or keep it loaded if you reconnect frequently)
    if (g_handle) {
        dlclose(g_handle);
        g_handle = nullptr;
    }
    p_main_from_str = nullptr;
    p_quit = nullptr;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_vpn_vray_itopvpn_service_MyVpnService_nativeIsRunning(JNIEnv *env, jclass clazz) {
    return g_running;
}
extern "C" JNIEXPORT void JNICALL
Java_vpn_vray_itopvpn_service_MyVpnService_nativeCloseFd(JNIEnv* env, jclass clazz, jint fd) {
    close(fd);
}
