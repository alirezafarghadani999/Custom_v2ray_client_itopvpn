package vpn.vray.itopvpn

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import vpn.vray.itopvpn.ApiConnector.objects.config

/**
 * A [DialogFragment] that displays a list of available VPN servers in a [RecyclerView].
 *
 * This fragment is responsible for showing the server list, handling user selection,
 * and communicating the selected server back to the calling activity or fragment
 * through the [ServerSelectListener] interface.
 */
class ServerListDialogFragment : DialogFragment() {

    /**
     * A listener interface for receiving callbacks when a server is selected.
     */
    interface ServerSelectListener {
        /**
         * Called when a server is selected from the list.
         * @param server The selected [config] object.
         */
        fun onServerSelected(server: config)
    }

    /** The listener instance that will receive server selection events. */
    var listener: ServerSelectListener? = null

    /** A callback function that is invoked when the [ServerAdapter] is created. */
    var onAdapterCreated: ((ServerAdapter) -> Unit)? = null

    private var serverList: ArrayList<config>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serverList = arguments?.getParcelableArrayList("SERVER_LIST")
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_server_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val recyclerView = view.findViewById<RecyclerView>(R.id.servers_recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(context)

        val serverList = arguments?.getParcelableArrayList<config>("SERVER_LIST")
        val pingResults = arguments?.getSerializable("PING_RESULTS") as? MutableMap<String, Long>

        if (serverList != null && pingResults != null) {
            val adapter = ServerAdapter(serverList, pingResults) { selectedServer ->
                listener?.onServerSelected(selectedServer)
                dismiss()
            }
            recyclerView.adapter = adapter
            onAdapterCreated?.invoke(adapter)
        }
    }

    companion object {
        /**
         * Creates a new instance of [ServerListDialogFragment].
         *
         * @param servers The list of servers to display.
         * @param pingResults A map of server configurations to their ping times.
         * @return A new instance of [ServerListDialogFragment] with the provided data.
         */
        fun newInstance(
            servers: ArrayList<config>,
            pingResults: MutableMap<String, Long>
        ): ServerListDialogFragment {
            val args = Bundle()
            args.putParcelableArrayList("SERVER_LIST", servers)
            args.putSerializable("PING_RESULTS", HashMap(pingResults))
            val fragment = ServerListDialogFragment()
            fragment.arguments = args
            return fragment
        }
    }
}