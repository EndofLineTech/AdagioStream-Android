package com.adagiostream.android.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Animated splash overlay that displays on top of the main content.
 * Shows an animated glow effect and app name, then fades out.
 */
@Composable
fun AdagioSplashOverlay(
    visible: Boolean,
    onFinished: () -> Unit,
) {
    if (!visible) return

    var showText by remember { mutableStateOf(false) }
    var fadeOut by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(500L)
        showText = true
        delay(2000L)
        fadeOut = true
        delay(500L)
        onFinished()
    }

    val overlayAlpha by animateFloatAsState(
        targetValue = if (fadeOut) 0f else 1f,
        animationSpec = tween(durationMillis = 500),
        label = "splashFade",
    )

    val textAlpha by animateFloatAsState(
        targetValue = if (showText) 1f else 0f,
        animationSpec = tween(durationMillis = 800),
        label = "textFade",
    )

    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val glowIntensity by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowPulse",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(overlayAlpha)
            .background(Color(0xFF1B2B4B)),
        contentAlignment = Alignment.Center,
    ) {
        // Glow layer 1
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(glowIntensity)
                .blur(radius = 40.dp)
                .background(Color(0xFF3A5A8A)),
        )

        // Glow layer 2 (subtler)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(glowIntensity * 0.6f)
                .blur(radius = 80.dp)
                .background(Color(0xFF4A7AB5)),
        )

        // App name
        Text(
            text = "Adagio Stream",
            fontSize = 32.sp,
            fontWeight = FontWeight.Light,
            color = Color.White,
            modifier = Modifier
                .alpha(textAlpha)
                .padding(bottom = 48.dp),
        )
    }
}
