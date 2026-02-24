package com.adagiostream.android.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adagiostream.android.model.AppearanceMode
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Buffer Duration",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Slider(
                value = settings.bufferDurationSeconds.toFloat(),
                onValueChange = { viewModel.updateBufferDuration(it.roundToInt()) },
                valueRange = 2f..60f,
                steps = 57,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${settings.bufferDurationSeconds}s",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 12.dp),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Appearance",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            AppearanceMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = settings.appearanceMode == mode,
                    onClick = { viewModel.updateAppearanceMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = AppearanceMode.entries.size,
                    ),
                ) {
                    Text(
                        text = when (mode) {
                            AppearanceMode.SYSTEM -> "System"
                            AppearanceMode.LIGHT -> "Light"
                            AppearanceMode.DARK -> "Dark"
                        },
                    )
                }
            }
        }
    }
}
