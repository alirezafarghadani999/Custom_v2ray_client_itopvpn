package vpn.vray.itopvpn

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import vpn.vray.itopvpn.ApiConnector.objects.config

class ServerListDialogFragment : DialogFragment() {


    // یک اینترفیس برای برگردوندن نتیجه به اکتیویتی
    interface ServerSelectListener {
        fun onServerSelected(server: config)
    }
    var listener: ServerSelectListener? = null
    var onAdapterCreated: ((ServerAdapter) -> Unit)? = null

    // لیستی که از اکتیویتی میاد
    private var serverList: ArrayList<config>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // گرفتن لیست سرورها از arguments
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
        // نتایج پینگ را از arguments دریافت کن
        val pingResults = arguments?.getSerializable("PING_RESULTS") as? MutableMap<String, Long>

        if (serverList != null && pingResults != null) {
            val adapter = ServerAdapter(serverList, pingResults, { selectedServer ->
                listener?.onServerSelected(selectedServer)
                dismiss()
            })
            recyclerView.adapter = adapter
            // آداپتور ساخته شده را به اکتیویتی اطلاع بده
            onAdapterCreated?.invoke(adapter)
        }
    }


    companion object {
        // newInstance را طوری تغییر بده که Map را هم به عنوان ورودی دوم بگیرد
        fun newInstance(
            servers: ArrayList<config>,
            pingResults: MutableMap<String, Long> // <-- این ورودی دوم باید اضافه شود
        ): ServerListDialogFragment {
            val args = Bundle()
            args.putParcelableArrayList("SERVER_LIST", servers)
            args.putSerializable("PING_RESULTS", HashMap(pingResults)) // Map باید Serializable باشد
            val fragment = ServerListDialogFragment()
            fragment.arguments = args
            return fragment
        }
    }
}