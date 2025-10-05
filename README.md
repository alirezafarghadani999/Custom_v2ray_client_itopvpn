# Custom V2Ray Client

An open-source, ad-supported VPN client for Android, featuring built-in support for the V2Ray protocol. This application allows users to connect to V2Ray servers, manage server configurations, and enjoy a secure and private internet connection. The client is designed to fetch server lists dynamically from a remote source and automatically select the fastest server for an optimal user experience.

## Features

- **V2Ray Protocol Support**: Natively supports V2Ray (VLESS) configurations.
- **Dynamic Server Loading**: Fetches server lists from a remote API, allowing for easy updates without requiring an app update.
- **Automatic Server Selection**: On startup, the app pings all servers and automatically selects the one with the lowest latency.
- **Manual Server Selection**: Users can manually choose from a list of available servers, sorted by ping.
- **Connection Status**: Displays real-time connection status, including connection state, ping, and traffic metrics (upload/download speeds).
- **Ad-Supported**: Integrates Google AdMob to display interstitial ads, providing a monetization option.
- **Foreground Service**: Runs the VPN connection as a foreground service with a persistent notification, ensuring stability.
- **Native Core Integration**: Utilizes a native Go-based library (`hevtunnel_jni`) for efficient and low-level network tunneling.

## Getting Started

### Prerequisites

- Android Studio (latest version recommended)
- Android SDK
- An Android device or emulator running Android API level 26 or higher

### Building and Running

1. **Clone the repository:**
   ```bash
   git clone https://github.com/your-username/Custom_v2ray_client_itopvpn.git
   ```

2. **Open the project in Android Studio:**
   - Launch Android Studio.
   - Select "Open an existing Android Studio project".
   - Navigate to the cloned repository and select the root `build.gradle.kts` file.

3. **Configure the API Endpoint:**
   - In `app/src/main/java/vpn/vray/itopvpn/SplashActivity.kt`, locate the `RetrofitInstance` object.
   - Update the `baseUrl` to point to your API endpoint for fetching server configurations:
     ```kotlin
     object RetrofitInstance {
         private val retrofit by lazy {
             Retrofit.Builder()
                 .baseUrl("YOUR_API_BASE_URL") // Replace with your API URL
                 .addConverterFactory(GsonConverterFactory.create())
                 .build()
         }
         // ...
     }
     ```

4. **Configure AdMob:**
   - In `app/src/main/res/values/strings.xml`, replace the placeholder AdMob App ID and Ad Unit ID with your own:
     ```xml
     <string name="ADMOB_APP_ID">ca-app-pub-your-app-id</string>
     <string name="AD_UNIT_ID">ca-app-pub-your-ad-unit-id</string>
     ```
   - Make sure your `google-services.json` file is correctly set up in the `app/` directory.

5. **Build and run the application:**
   - Click the "Run" button in Android Studio or use the `Shift + F10` shortcut.
   - Select your target device or emulator.

## Project Structure

- **`app/src/main/java/vpn/vray/itopvpn/`**: The main package containing the application's source code.
  - **`ApiConnector/`**: Contains Retrofit interfaces and data classes for API communication.
    - `GetConfig.kt`: Defines the API endpoints for fetching server configurations.
    - `objects/config.kt`: A data class representing a single VPN server configuration.
  - **`activities/`**: Contains additional activities (e.g., `LogWindow.kt`).
  - **`service/`**: Contains the `MyVpnService` class, which manages the VPN connection as a foreground service.
  - **`MainActivity.kt`**: The main screen of the application, handling user interactions and displaying connection status.
  - **`SplashActivity.kt`**: The startup screen, responsible for initialization, server fetching, and ad loading.
  - **`ServerAdapter.kt`**: A `RecyclerView.Adapter` for displaying the server list.
  - **`ServerListDialogFragment.kt`**: A dialog fragment that shows the list of available servers.
  - **`V2rayManager.kt`**: A singleton object that manages the V2Ray core, including starting, stopping, and converting configurations.
- **`app/src/main/res/`**: Contains all application resources, such as layouts, drawables, and strings.
- **`app/src/main/cpp/`**: Contains native C/C++ source code, including the JNI bridge for the tunneling library.
- **`app/src/main/jniLibs/`**: Contains pre-compiled native libraries (`.so` files).

## Dependencies

- **[Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines)**: For asynchronous programming.
- **[Retrofit](https://square.github.io/retrofit/)**: For type-safe HTTP client and API communication.
- **[Gson](https://github.com/google/gson)**: For JSON serialization and deserialization.
- **[Google AdMob](https://developers.google.com/admob/android/quick-start)**: For displaying interstitial ads.
- **[LibV2Ray](https://github.com/2dust/v2rayN/tree/master/v2rayN/lib) (via JNI)**: The underlying V2Ray core library.

## Contributing

Contributions are welcome! If you have any ideas, suggestions, or bug reports, please open an issue or submit a pull request.

## License

This project is licensed under the MIT License. See the `LICENSE` file for more details.