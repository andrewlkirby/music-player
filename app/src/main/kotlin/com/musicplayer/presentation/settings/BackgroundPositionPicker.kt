package com.musicplayer.presentation.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.rememberAsyncImagePainter
import com.musicplayer.presentation.theme.AppIcons

/**
 * Full-screen drag-to-reposition picker. Renders with the same
 * ContentScale.Crop + BiasAlignment combo MainActivity uses for the real
 * background, so what you drag to here is exactly what you get.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundPositionPickerDialog(
    imagePath: String,
    initialBiasX: Float,
    initialBiasY: Float,
    onDismiss: () -> Unit,
    onSave: (biasX: Float, biasY: Float) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text("Position Background") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(AppIcons.Close, "Cancel")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black,
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White
                    )
                )

                var containerSize by remember { mutableStateOf(IntSize.Zero) }
                // Drawn unconditionally so Coil always has a placed target to size/load
                // against — gating placement on intrinsicSize first never resolves.
                val painter = rememberAsyncImagePainter(model = imagePath)
                val intrinsicSize = painter.intrinsicSize

                var biasX by remember { mutableStateOf(initialBiasX) }
                var biasY by remember { mutableStateOf(initialBiasY) }

                val ready = intrinsicSize.isSpecified &&
                    intrinsicSize.width > 0f && intrinsicSize.height > 0f &&
                    containerSize.width > 0 && containerSize.height > 0

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onSizeChanged { containerSize = it }
                        .pointerInput(ready, intrinsicSize, containerSize) {
                            if (!ready) return@pointerInput
                            val scale = maxOf(
                                containerSize.width / intrinsicSize.width,
                                containerSize.height / intrinsicSize.height
                            )
                            // Half the overflow on each axis — how many px the crop
                            // window can travel from center to either edge.
                            val halfOverflowX = (intrinsicSize.width * scale - containerSize.width) / 2f
                            val halfOverflowY = (intrinsicSize.height * scale - containerSize.height) / 2f
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                if (halfOverflowX > 0f) {
                                    biasX = (biasX - dragAmount.x / halfOverflowX).coerceIn(-1f, 1f)
                                }
                                if (halfOverflowY > 0f) {
                                    biasY = (biasY - dragAmount.y / halfOverflowY).coerceIn(-1f, 1f)
                                }
                            }
                        }
                ) {
                    if (ready) {
                        Image(
                            painter = painter,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            alignment = BiasAlignment(biasX, biasY),
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Image(
                            painter = painter,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }

                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Drag the image to choose what shows behind your menus",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                        ) {
                            Text("Cancel")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { onSave(biasX, biasY) }) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}
