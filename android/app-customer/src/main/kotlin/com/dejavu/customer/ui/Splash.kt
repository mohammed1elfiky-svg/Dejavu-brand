package com.dejavu.customer.ui

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.dejavu.customer.R
import kotlinx.coroutines.delay

/**
 * The gold DEJAVU logo on white for about a second when the app opens. Android 12+ already
 * showed it in the system splash, so there it starts fully visible instead of fading in.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val alpha = remember { Animatable(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 1f else 0f) }
    LaunchedEffect(Unit) {
        alpha.animateTo(1f, tween(durationMillis = 450))
        delay(550)
        onFinished()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.White)
            // Swallow taps meant for the screen underneath.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.splash_logo),
            contentDescription = "DEJAVU",
            modifier = Modifier.width(220.dp).alpha(alpha.value),
        )
    }
}
