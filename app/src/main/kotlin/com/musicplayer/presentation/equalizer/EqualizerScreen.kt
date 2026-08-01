package com.musicplayer.presentation.equalizer

import android.media.audiofx.Equalizer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EqUiState(
    val enabled: Boolean = false,
    val bands: List<EqBand> = emptyList(),
    val presets: List<String> = emptyList(),
    val selectedPresetIndex: Int = -1,
    val isSupported: Boolean = true
)

data class EqBand(
    val index: Short,
    val centerFreqHz: Int,     // in milliHz → divide by 1000 for Hz
    val gainMilliBel: Short,   // current gain
    val minMilliBel: Short,
    val maxMilliBel: Short
)

@HiltViewModel
class EqualizerViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(EqUiState())
    val uiState: StateFlow<EqUiState> = _uiState.asStateFlow()

    private var equalizer: Equalizer? = null

    init {
        initEqualizer()
    }

    private fun initEqualizer() {
        try {
            val eq = Equalizer(0, 0) // priority=0, audioSession=0 (global)
            equalizer = eq

            val numBands = eq.numberOfBands
            val bands = (0 until numBands).map { i ->
                val idx = i.toShort()
                EqBand(
                    index = idx,
                    centerFreqHz = eq.getCenterFreq(idx),
                    gainMilliBel = eq.getBandLevel(idx),
                    minMilliBel = eq.bandLevelRange[0],
                    maxMilliBel = eq.bandLevelRange[1]
                )
            }

            val presets = (0 until eq.numberOfPresets).map { eq.getPresetName(it.toShort()) }

            _uiState.update {
                it.copy(
                    enabled = eq.enabled,
                    bands = bands,
                    presets = presets,
                    isSupported = true
                )
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(isSupported = false) }
        }
    }

    fun setEnabled(enabled: Boolean) {
        equalizer?.enabled = enabled
        _uiState.update { it.copy(enabled = enabled) }
    }

    fun setBandGain(bandIndex: Short, gainMilliBel: Short) {
        try {
            equalizer?.setBandLevel(bandIndex, gainMilliBel)
            _uiState.update { state ->
                state.copy(
                    bands = state.bands.map { band ->
                        if (band.index == bandIndex) band.copy(gainMilliBel = gainMilliBel) else band
                    },
                    selectedPresetIndex = -1 // custom
                )
            }
        } catch (e: Exception) { /* ignore */ }
    }

    fun applyPreset(presetIndex: Short) {
        try {
            equalizer?.usePreset(presetIndex)
            val eq = equalizer ?: return
            val updatedBands = _uiState.value.bands.map { band ->
                band.copy(gainMilliBel = eq.getBandLevel(band.index))
            }
            _uiState.update { it.copy(bands = updatedBands, selectedPresetIndex = presetIndex.toInt()) }
        } catch (e: Exception) { /* ignore */ }
    }

    fun resetFlat() {
        val eq = equalizer ?: return
        val mid = ((eq.bandLevelRange[0] + eq.bandLevelRange[1]) / 2).toShort()
        _uiState.value.bands.forEach { band ->
            try { eq.setBandLevel(band.index, mid) } catch (e: Exception) {}
        }
        _uiState.update { state ->
            state.copy(
                bands = state.bands.map { it.copy(gainMilliBel = mid) },
                selectedPresetIndex = -1
            )
        }
    }

    override fun onCleared() {
        equalizer?.release()
        super.onCleared()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreen(
    onBack: () -> Unit,
    viewModel: EqualizerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Equalizer") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.resetFlat() }) {
                        Text("Reset")
                    }
                }
            )
        }
    ) { padding ->
        if (!state.isSupported) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.ErrorOutline,
                        null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Equalizer not supported on this device",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Enable toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Equalizer", style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = state.enabled,
                    onCheckedChange = { viewModel.setEnabled(it) }
                )
            }

            Spacer(Modifier.height(16.dp))

            // Presets
            if (state.presets.isNotEmpty()) {
                Text("Preset", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = if (state.selectedPresetIndex >= 0 && state.selectedPresetIndex < state.presets.size)
                            state.presets[state.selectedPresetIndex]
                        else "Custom",
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        enabled = state.enabled
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        state.presets.forEachIndexed { index, preset ->
                            DropdownMenuItem(
                                text = { Text(preset) },
                                onClick = {
                                    viewModel.applyPreset(index.toShort())
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Band sliders
            Text("Bands", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))

            state.bands.forEach { band ->
                BandSlider(
                    band = band,
                    enabled = state.enabled,
                    onGainChange = { gain -> viewModel.setBandGain(band.index, gain) }
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun BandSlider(
    band: EqBand,
    enabled: Boolean,
    onGainChange: (Short) -> Unit
) {
    val freqLabel = when {
        band.centerFreqHz >= 1_000_000 -> "${band.centerFreqHz / 1_000_000}kHz"
        else -> "${band.centerFreqHz / 1000}Hz"
    }

    val gainDb = band.gainMilliBel / 100f  // milliBel → dB

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                freqLabel,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(56.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = band.gainMilliBel.toFloat(),
                onValueChange = { onGainChange(it.toInt().toShort()) },
                valueRange = band.minMilliBel.toFloat()..band.maxMilliBel.toFloat(),
                enabled = enabled,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${if (gainDb >= 0) "+" else ""}${"%.1f".format(gainDb)}dB",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(52.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
