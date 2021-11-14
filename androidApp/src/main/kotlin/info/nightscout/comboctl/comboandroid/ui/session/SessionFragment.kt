package info.nightscout.comboctl.comboandroid.ui.session

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.accompanist.flowlayout.FlowMainAxisAlignment
import com.google.accompanist.flowlayout.FlowRow
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
        binding.buttonPanelCompose.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                SessionContent()
            }
        }
    }

    @OptIn(ExperimentalAnimationApi::class)
    @Composable
    private fun SessionContent() {
        MaterialTheme {
            Column(
                Modifier
                    .fillMaxWidth()
                    .wrapContentSize(align = Alignment.Center)
            ) {
                var expanded by remember { mutableStateOf(false) }


                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(8.dp)
                ) {
                    Button(onClick = { expanded = !expanded }) {
                        Text(text = if (expanded) "Less" else "More")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(initialAlpha = 0.5f),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    ExtendedButtonPanel(
                        onSetTbrClicked = { viewModel.setTbrClicked() },
                        onDeliverBolusClicked = { viewModel.onCMBolusClicked("5") },
                        onGetDateAndTimeClicked = viewModel::onReadTimeClicked,
                        onHistoryDeltaReadClicked = viewModel::onHistoryDeltaReadClicked,
                        onReadQuickInfoClicked = viewModel::onReadQuickInfoClicked,
                        onSetRandomBasalProfileClicked = viewModel::onSetRandomBasalProfileClicked,
                        onReadBasalProfile = viewModel::onReadBasalProfileClicked
                    )
                }
            }
        }
    }

    @Composable
    fun ExtendedButtonPanel(
        onSetTbrClicked: () -> Unit,
        onDeliverBolusClicked: () -> Unit,
        onGetDateAndTimeClicked: () -> Unit,
        onHistoryDeltaReadClicked: () -> Unit,
        onReadQuickInfoClicked: () -> Unit,
        onSetRandomBasalProfileClicked: () -> Unit,
        onReadBasalProfile: () -> Unit,
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(0.95F),
            mainAxisSpacing = 4.dp,
            crossAxisSpacing = 4.dp,
            mainAxisAlignment = FlowMainAxisAlignment.Center
        ) {
            Button(onClick = onSetTbrClicked) {
                Text(text = "Set TBR")
            }
            Button(onClick = onDeliverBolusClicked) {
                Text(text = "Deliver Bolus")
            }
            Button(onClick = onGetDateAndTimeClicked) {
                Text(text = "Get Date & Time")
            }
            Button(onClick = onHistoryDeltaReadClicked) {
                Text(text = "Read History")
            }
            Button(onClick = onReadQuickInfoClicked) {
                Text(text = "Read QuickInfo")
            }
            Button(onClick = onSetRandomBasalProfileClicked) {
                Text(text = "Set Random Basal Profile")
            }
            Button(onClick = onReadBasalProfile) {
                Text(text = "Read Basal Profile")
            }
        }

    }

    @Preview(showSystemUi = true)
    @Composable
    fun Preview() {
        SessionContent()
    }
}
