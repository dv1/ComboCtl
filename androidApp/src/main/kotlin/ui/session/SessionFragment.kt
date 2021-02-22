package info.nightscout.comboctl.comboandroid.ui.session

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import info.nightscout.comboctl.comboandroid.R

class SessionFragment : Fragment() {

    private val viewModel: SessionViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_session, container, false)
        val textView: TextView = root.findViewById(R.id.text_session)
        viewModel.text.observe(viewLifecycleOwner) {
            textView.text = it
        }
        return root
    }
}
