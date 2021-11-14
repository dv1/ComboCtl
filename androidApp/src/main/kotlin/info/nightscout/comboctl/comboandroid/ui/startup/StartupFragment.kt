package info.nightscout.comboctl.comboandroid.ui.startup

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import info.nightscout.comboctl.comboandroid.R
import info.nightscout.comboctl.comboandroid.databinding.FragmentStartupBinding
import info.nightscout.comboctl.comboandroid.ui.base.BaseDatabindingFragment

class StartupFragment : BaseDatabindingFragment<FragmentStartupBinding, StartupViewModel>() {
    override val viewModel: StartupViewModel by viewModels()
    override val layoutResId = R.layout.fragment_startup

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.connectCta.setOnClickListener {
            findNavController().navigate(R.id.nav_session)
        }
        binding.pairCta.setOnClickListener {
            findNavController().navigate(R.id.nav_pairing)
        }
    }
}
