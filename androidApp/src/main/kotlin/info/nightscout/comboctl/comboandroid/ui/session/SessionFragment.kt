package info.nightscout.comboctl.comboandroid.ui.session

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import info.nightscout.comboctl.comboandroid.R
import info.nightscout.comboctl.comboandroid.databinding.FragmentSessionBinding
import info.nightscout.comboctl.comboandroid.ui.base.BaseFragment

class SessionFragment : BaseFragment<FragmentSessionBinding, SessionViewModel>() {
    override val viewModel: SessionViewModel by viewModels()
    override val layoutResId = R.layout.fragment_session

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.startLifeCycle()
        viewModel.state.observe(viewLifecycleOwner) {
            when (it) {
                SessionViewModel.State.NO_PUMP_FOUND -> {
                    Toast.makeText(requireContext(), "Discovery stopped!", Toast.LENGTH_LONG).show()
                    findNavController().navigate(R.id.nav_startup)
                }
                else -> Unit
            }
        }
        viewModel.timeLiveData.observe(viewLifecycleOwner) {
            Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
        }
    }
}
