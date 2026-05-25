package com.adagiostream.android.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adagiostream.android.R
import kotlinx.coroutines.delay

/**
 * Animated splash overlay that displays on top of the main content.
 * Shows a themed splash image with glow effects and app name, then fades out.
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
        delay(800L)
        onFinished()
    }

    val overlayAlpha by animateFloatAsState(
        targetValue = if (fadeOut) 0f else 1f,
        animationSpec = tween(durationMillis = 800),
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
            animation = tween(durationMillis = 1500),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowPulse",
    )

    val backgroundColor = MaterialTheme.colorScheme.background

    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(overlayAlpha)
            .background(backgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        // Main splash image — positioned lower, scaled up like iOS
        Image(
            painter = painterResource(R.drawable.splash_background),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = 2f; scaleY = 2f },
            contentScale = ContentScale.Crop,
            alignment = BiasAlignment(0f, 0.3f),
        )

        // Glow layer 1 (tighter blur)
        Image(
            painter = painterResource(R.drawable.splash_background),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .alpha(glowIntensity)
                .blur(radius = 10.dp)
                .graphicsLayer { scaleX = 2f; scaleY = 2f },
            contentScale = ContentScale.Crop,
            alignment = BiasAlignment(0f, 0.3f),
            colorFilter = ColorFilter.tint(
                color = backgroundColor.copy(alpha = 0f),
                blendMode = BlendMode.Screen,
            ),
        )

        // Glow layer 2 (softer, wider blur)
        Image(
            painter = painterResource(R.drawable.splash_background),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .alpha(glowIntensity * 0.6f)
                .blur(radius = 20.dp)
                .graphicsLayer { scaleX = 2f; scaleY = 2f },
            contentScale = ContentScale.Crop,
            alignment = BiasAlignment(0f, 0.3f),
            colorFilter = ColorFilter.tint(
                color = backgroundColor.copy(alpha = 0f),
                blendMode = BlendMode.Screen,
            ),
        )

        // App name
        Text(
            text = "Adagio Stream",
            fontSize = 34.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .alpha(textAlpha)
                .padding(bottom = 120.dp),
        )
    }
}
