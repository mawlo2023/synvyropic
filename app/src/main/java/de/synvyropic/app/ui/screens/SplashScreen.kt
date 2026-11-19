package de.synvyropic.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import de.synvyropic.app.BuildConfig
import de.synvyropic.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Animierter Start mit dem Synvyropic-Logo (Antippen überspringt). */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(0.9f) }
    LaunchedEffect(Unit) {
        launch { scale.animateTo(1f, tween(1400, easing = FastOutSlowInEasing)) }
        alpha.animateTo(1f, tween(700))
        delay(900)
        alpha.animateTo(0f, tween(350))
        onDone()
    }
    Box(
        Modifier.fillMaxSize().background(Color(0xFF01010C))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDone() },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.logo_full),
            contentDescription = "Synvyropic",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp).padding(24.dp)
                .scale(scale.value).alpha(alpha.value),
        )
        Text(
            "Version ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp).alpha(alpha.value),
        )
    }
}
