package info.nightscout.comboctl.comboandroid.ui.pairing

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import info.nightscout.comboctl.comboandroid.R
import info.nightscout.comboctl.comboandroid.databinding.FragmentPairingBinding
import info.nightscout.comboctl.comboandroid.ui.base.BaseFragment

class PairingFragment : BaseFragment<FragmentPairingBinding, PairingViewModel>() {

    override val viewModel: PairingViewModel by viewModels()
    override val layoutResId = R.layout.fragment_pairing

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.startLifeCycle()
    }
}
