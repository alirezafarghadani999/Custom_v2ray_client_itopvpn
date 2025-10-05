package vpn.vray.itopvpn

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import vpn.vray.itopvpn.ApiConnector.objects.config

/**
 * A [RecyclerView.Adapter] for displaying a list of VPN servers.
 *
 * This adapter is responsible for binding the server data, including names and ping results,
 * to the views in the server list dialog. It also handles item click events.
 *
 * @property serverList The list of server configurations to display.
 * @property pingResults A map containing the ping latency for each server's configuration string.
 * @property onItemClick A lambda function to be invoked when a server item is clicked.
 */
class ServerAdapter(
    private val serverList: List<config>,
    private val pingResults: Map<String, Long>,
    private val onItemClick: (config) -> Unit
) : RecyclerView.Adapter<ServerAdapter.ServerViewHolder>() {

    /**
     * A [RecyclerView.ViewHolder] that holds the views for a single server item.
     *
     * @param itemView The root view of the item layout.
     */
    inner class ServerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.server_icon)
        val name: TextView = itemView.findViewById(R.id.server_name_text)
        val status: TextView = itemView.findViewById(R.id.server_ping)

        /**
         * Binds a server's data to the views in the ViewHolder.
         *
         * @param server The [config] object containing the server's details.
         */
        fun bind(server: config) {
            name.text = server.name
            status.text = when (val delay = pingResults[server.config]) {
                -1L -> "N/A"
                -2L -> "Invalid"
                null -> "..."
                else -> "${delay}ms"
            }
            itemView.setOnClickListener { onItemClick(server) }
        }
    }

    /**
     * Creates a new [ServerViewHolder] by inflating the item layout.
     *
     * @param parent The [ViewGroup] into which the new view will be added.
     * @param viewType The view type of the new view.
     * @return A new [ServerViewHolder] that holds a view for the item.
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_server, parent, false)
        return ServerViewHolder(view)
    }

    /**
     * Binds the data at a specific position to the given [ServerViewHolder].
     *
     * @param holder The [ServerViewHolder] which should be updated.
     * @param position The position of the item within the adapter's data set.
     */
    override fun onBindViewHolder(holder: ServerViewHolder, position: Int) {
        holder.bind(serverList[position])
    }

    /**
     * Returns the total number of items in the data set held by the adapter.
     *
     * @return The total number of items in this adapter.
     */
    override fun getItemCount() = serverList.size
}