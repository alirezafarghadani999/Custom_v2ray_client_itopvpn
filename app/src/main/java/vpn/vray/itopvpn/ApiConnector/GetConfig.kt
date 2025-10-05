package vpn.vray.itopvpn.ApiConnector

import retrofit2.http.GET
import vpn.vray.itopvpn.ApiConnector.objects.config

import retrofit2.http.GET
import vpn.vray.itopvpn.ApiConnector.objects.config

/**
 * Defines the API endpoints for fetching server configurations using Retrofit.
 *
 * This interface outlines the HTTP requests related to retrieving VPN server
 * lists from the remote server.
 */
interface GetConfig {

    /**
     * Fetches the main list of VPN server configurations for the application.
     *
     * This endpoint is typically called after the main application screen is loaded.
     *
     * @return A list of [config] objects representing the available VPN servers.
     */
    @GET("/get_config_app/")
    suspend fun GetAppConfig(): List<config>

    /**
     * Fetches a specific or priority server configuration for the splash screen.
     *
     * This might be used to get a default or recommended server quickly on app startup.
     *
     * @return A list of [config] objects, often containing a single server.
     */
    @GET("/get_config_splash/")
    suspend fun GetSplashConfig(): List<config>
}