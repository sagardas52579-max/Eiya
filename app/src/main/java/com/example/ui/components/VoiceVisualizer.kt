package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun VoiceVisualizer(
    isRecording: Boolean,
    isSpeaking: Boolean,
    amplitude: Float,
    statusText: String,
    onOrbClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb_pulse")

    val baseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "base_scale"
    )

    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val animatedAmp = remember { Animatable(0f) }
    LaunchedEffect(amplitude) {
        animatedAmp.animateTo(amplitude, tween(100))
    }

    val dynamicScale = baseScale + (animatedAmp.value * 0.45f)

    // Palette: warm coral, magenta, golden amber, deep violet
    val primaryColor = when {
        isSpeaking -> Color(0xFFFF5252) // Vibrant Coral/Red when speaking
        isRecording -> Color(0xFF00E676) // Bright Green when listening
        else -> Color(0xFF7C4DFF) // Deep Violet/Purple when idle
    }

    val secondaryColor = when {
        isSpeaking -> Color(0xFFFFB300) // Golden Amber
        isRecording -> Color(0xFF00B0FF) // Light Blue
        else -> Color(0xFFE040FB) // Magenta
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(170.dp)
                .testTag("voice_visualizer_orb")
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOrbClick
                ),
            contentAlignment = Alignment.Center
        ) {
            // Background Canvas for glowing particle rings
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val baseRadius = (size.minDimension / 2f) * 0.72f * dynamicScale

                // Outer ambient glow ring
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = 0.35f),
                            secondaryColor.copy(alpha = 0.15f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = baseRadius * 1.35f
                    ),
                    radius = baseRadius * 1.35f,
                    center = center
                )

                // Middle pulsing glow
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            secondaryColor.copy(alpha = 0.6f),
                            primaryColor.copy(alpha = 0.3f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = baseRadius * 1.15f
                    ),
                    radius = baseRadius * 1.15f,
                    center = center
                )

                // Particle orbit points
                val particleCount = 12
                val particleRadius = baseRadius * 1.12f
                val radOffset = Math.toRadians(rotationAngle.toDouble())
                for (i in 0 until particleCount) {
                    val angle = (2 * Math.PI * i / particleCount) + radOffset
                    val x = center.x + (particleRadius * cos(angle)).toFloat()
                    val y = center.y + (particleRadius * sin(angle)).toFloat()
                    val pAlpha = if (isSpeaking || isRecording) 0.8f else 0.4f
                    drawCircle(
                        color = if (i % 2 == 0) primaryColor.copy(alpha = pAlpha) else secondaryColor.copy(alpha = pAlpha),
                        radius = 4.5f * (1f + animatedAmp.value),
                        center = Offset(x, y)
                    )
                }

                // Core gradient orb
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.95f),
                            secondaryColor.copy(alpha = 0.9f),
                            primaryColor.copy(alpha = 0.95f)
                        ),
                        center = Offset(center.x - 12f, center.y - 12f),
                        radius = baseRadius
                    ),
                    radius = baseRadius,
                    center = center
                )
            }

            // Central icon
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isSpeaking -> Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = "Arushi Speaking",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                    isRecording -> Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = "Listening",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                    else -> Icon(
                        imageVector = Icons.Default.MicNone,
                        contentDescription = "Arushi Ready",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = when {
                isSpeaking -> "Arushi is speaking (Tap to interrupt)"
                isRecording -> "Listening to your voice..."
                else -> statusText
            },
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (isSpeaking || isRecording) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 13.sp
            ),
            color = when {
                isSpeaking -> Color(0xFFFF5252)
                isRecording -> Color(0xFF00E676)
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}
