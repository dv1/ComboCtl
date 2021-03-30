package info.nightscout.comboctl.comboandroid.ui.session

import androidx.fragment.app.viewModels
import info.nightscout.comboctl.comboandroid.R
import info.nightscout.comboctl.comboandroid.databinding.FragmentSessionBinding
import info.nightscout.comboctl.comboandroid.ui.base.BaseFragment

class SessionFragment : BaseFragment<FragmentSessionBinding, SessionViewModel>() {
    override val viewModel: SessionViewModel by viewModels()
    override val layoutResId = R.layout.fragment_session
}
