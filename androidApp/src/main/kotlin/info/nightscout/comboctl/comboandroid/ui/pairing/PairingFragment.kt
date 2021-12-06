package info.nightscout.comboctl.comboandroid.ui.pairing

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import info.nightscout.comboctl.comboandroid.R
import info.nightscout.comboctl.comboandroid.databinding.FragmentPairingBinding
import info.nightscout.comboctl.comboandroid.ui.base.BaseDatabindingFragment

class PairingFragment : BaseDatabindingFragment<FragmentPairingBinding, PairingViewModel>() {

    override val viewModel: PairingViewModel by viewModels()
    override val layoutResId = R.layout.fragment_pairing

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.startLifeCycle()
        viewModel.state.observe(viewLifecycleOwner) {
            when (it) {
                PairingViewModel.State.UNINITIALIZED -> ""
                PairingViewModel.State.PAIRING -> "Scanning for device ..."
                PairingViewModel.State.PIN_ENTRY -> "Please enter 10 digit pin"
                PairingViewModel.State.FINISHING_PAIRING -> "Wrapping up..."
                PairingViewModel.State.PAIRING_FINISHED -> {
                    findNavController().navigate(R.id.nav_startup)
                    ""
                }
                PairingViewModel.State.PAIRING_CANCELLED -> {
                    Toast.makeText(requireContext(), "Pairing cancelled!", Toast.LENGTH_LONG).show()
                    navigateBack(); ""
                }
                PairingViewModel.State.DISCOVERY_STOPPED -> {
                    Toast.makeText(requireContext(), "Discovery stopped!", Toast.LENGTH_LONG).show()
                    navigateBack(); "" }
                else -> ""
            }.let { binding.pairingHeader.text = it }
        }
    }

    private fun navigateBack() {
        findNavController().navigate(R.id.nav_startup)
    }
}
