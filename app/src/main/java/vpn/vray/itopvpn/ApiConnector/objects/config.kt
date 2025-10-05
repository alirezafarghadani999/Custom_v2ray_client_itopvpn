package vpn.vray.itopvpn.ApiConnector.objects
import android.annotation.SuppressLint
import kotlinx.parcelize.Parcelize
import android.os.Parcelable

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents a VPN server configuration.
 *
 * This data class holds the details for a single VPN server, implementing [Parcelable]
 * to allow instances to be passed between Android components (e.g., Activities, Fragments).
 *
 * @property id The unique identifier for the server configuration.
 * @property name The display name of the server (e.g., "US-East-1").
 * @property flag A string identifier for the country flag, often a URL or an emoji.
 * @property config The V2Ray configuration string, typically a VLESS URI.
 */
@SuppressLint("ParcelCreator")
@Parcelize
data class config(
    val id: Int,
    val name: String,
    val flag: String,
    val config: String
) : Parcelable
