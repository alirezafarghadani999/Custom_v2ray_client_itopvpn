package vpn.vray.itopvpn.ApiConnector.objects
import android.annotation.SuppressLint
import kotlinx.parcelize.Parcelize
import android.os.Parcelable

@SuppressLint("ParcelCreator")
@Parcelize
data class config(
    val id :Int,
    val name: String,
    val flag: String,
    val config: String
): Parcelable
