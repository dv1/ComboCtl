package info.nightscout.comboctl.comboandroid.ui.pairing

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import info.nightscout.comboctl.comboandroid.R

class PairingFragment : Fragment() {
    private lateinit var pairingViewModel: PairingViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        pairingViewModel =
            ViewModelProvider(this).get(PairingViewModel::class.java)
        val root = inflater.inflate(R.layout.fragment_gallery, container, false)
        val textView: TextView = root.findViewById(R.id.text_gallery)
        pairingViewModel.text.observe(viewLifecycleOwner) { textView.text = it }
        return root
    }
}
