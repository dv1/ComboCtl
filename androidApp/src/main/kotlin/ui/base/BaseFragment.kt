package info.nightscout.comboctl.comboandroid.ui.base

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import androidx.databinding.library.baseAdapters.BR
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel

abstract class BaseFragment<VDB : ViewDataBinding, VM : ViewModel> : Fragment() {
    abstract val layoutResId: Int
    abstract val viewModel: VM

    private var _binding: VDB? = null

    val binding: VDB
        get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = DataBindingUtil.inflate(inflater, layoutResId, container, false)
        return with(binding) {
            setVariable(BR.vm, viewModel)
            lifecycleOwner = this@BaseFragment
            root
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
