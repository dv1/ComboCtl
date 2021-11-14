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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import info.nightscout.comboctl.comboandroid.ui.compose.ComboDisplay
import info.nightscout.comboctl.comboandroid.ui.compose.SimpleGrid

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

    @Composable
    private fun SessionContent() {
        MaterialTheme {
            ConnectedScreen()
        }
    }

    @OptIn(ExperimentalAnimationApi::class)
    @Composable
    private fun ConnectedScreen() {
        Column(
            Modifier
                .fillMaxWidth()
                .wrapContentSize(align = Alignment.Center)
        ) {
            var expanded by remember { mutableStateOf(false) }


            Spacer(modifier = Modifier.height(16.dp))
            val frame by viewModel.frameLiveData.observeAsState()
            Surface(
                Modifier
                    .fillMaxWidth()
                    .wrapContentSize(align = Alignment.Center)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.DarkGray)
                    .padding(8.dp)
            ) {
                ComboDisplay(frame = frame)
            }
            Spacer(modifier = Modifier.height(16.dp))
            StandardButtonPanel()
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
                ExtendedButtonPanel()
            }
        }
    }

    @Composable
    fun StandardButtonPanel() {

        SimpleGrid(
            columns = 3, spacing = 4.dp, modifier = Modifier
                .fillMaxWidth()
                .wrapContentSize(Alignment.Center)
        ) {
            Button(onClick = { viewModel.onMenuClicked() }) {
                Text(text = "MENU")
            }
            Button(onClick = { viewModel.onUpClicked() }) {
                Text(text = "UP")
            }
            Button(onClick = { viewModel.onBackClicked() }) {
                Text(text = "BACK")
            }
            Button(onClick = { viewModel.onCheckClicked() }) {
                Text(text = "CHECK")
            }
            Button(onClick = { viewModel.onDownClicked() }) {
                Text(text = "DOWN")
            }
            Button(onClick = { viewModel.onUpDownClicked() }) {
                Text(text = "UP-DOWN")
            }
        }
    }

    @Composable
    fun ExtendedButtonPanel() {
        FlowRow(
            modifier = Modifier.fillMaxWidth(0.95F),
            mainAxisSpacing = 4.dp,
            crossAxisSpacing = 4.dp,
            mainAxisAlignment = FlowMainAxisAlignment.Center
        ) {
            Button(onClick = viewModel::setTbrClicked) {
                Text(text = "Set TBR")
            }
            Button(onClick = { viewModel.onCMBolusClicked("1") }) {
                Text(text = "Deliver Bolus")
            }
            Button(onClick = viewModel::onReadTimeClicked) {
                Text(text = "Get Date & Time")
            }
            Button(onClick = viewModel::onHistoryDeltaReadClicked) {
                Text(text = "Read History")
            }
            Button(onClick = viewModel::onReadQuickInfoClicked) {
                Text(text = "Read QuickInfo")
            }
            Button(onClick = viewModel::onSetRandomBasalProfileClicked) {
                Text(text = "Set Random Basal Profile")
            }
            Button(onClick = viewModel::onReadBasalProfileClicked) {
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
