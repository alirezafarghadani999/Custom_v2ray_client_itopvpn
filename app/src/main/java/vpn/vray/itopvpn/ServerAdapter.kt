package vpn.vray.itopvpn

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import vpn.vray.itopvpn.ApiConnector.objects.config

class ServerAdapter(
    private val serverList: List<config>,
    private val pingResults: Map<String, Long>, // <-- نتایج پینگ را اینجا می‌گیریم
    private val onItemClick: (config) -> Unit// یک تابع برای مدیریت کلیک‌ها){}
) : RecyclerView.Adapter<ServerAdapter.ServerViewHolder>() {

    // این کلاس، ویوهای هر ردیف رو نگه میداره
    inner class ServerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.server_icon)
        val name: TextView = itemView.findViewById(R.id.server_name_text)
        val status: TextView = itemView.findViewById(R.id.server_ping)

        fun bind(server: config) {
            name.text = server.name // اسم سرور رو به TextView میدیم

            if (pingResults.containsKey(server.config)) {
                when (val delay = pingResults[server.config]) {
                    -1L -> status.text = "-1"
                    -2L -> status.text = "invalid"
                    null -> status.text = "" // یا "در حال تست..."
                    else -> status.text = "${delay}ms"
                }
            } else {
                status.text = "..." // هنوز تست نشده
            }

            itemView.setOnClickListener {
                onItemClick(server)
            }
        }
    }

    // اینجا layout هر آیتم رو به RecyclerView معرفی می‌کنه
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_server, parent, false)
        return ServerViewHolder(view)
    }

    // این تابع به تعداد آیتم‌های لیست صدا زده میشه تا اطلاعات رو به ویوها بچسبونه
    override fun onBindViewHolder(holder: ServerViewHolder, position: Int) {
        holder.bind(serverList[position])
    }

    // تعداد کل آیتم‌های لیست رو برمی‌گردونه
    override fun getItemCount() = serverList.size
}