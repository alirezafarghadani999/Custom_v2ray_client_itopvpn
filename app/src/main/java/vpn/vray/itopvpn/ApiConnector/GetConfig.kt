package vpn.vray.itopvpn.ApiConnector

import retrofit2.http.GET
import vpn.vray.itopvpn.ApiConnector.objects.config

interface GetConfig {

    @GET("/get_config_app/")
    suspend fun GetAppConfig() : List<config>

    @GET("/get_config_splash/")
    suspend fun GetSplashConfig() : List<config>
}