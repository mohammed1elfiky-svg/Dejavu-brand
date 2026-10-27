package com.dejavu.customer.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dejavu.customer.R

/** The DEJAVU logo of the app icon (gold), for headers inside the app. */
@Composable
fun BrandMark(modifier: Modifier = Modifier, height: Dp = 28.dp) {
    Image(
        painter = painterResource(R.drawable.splash_logo),
        contentDescription = "DEJAVU",
        contentScale = ContentScale.Fit,
        modifier = modifier.height(height),
    )
}
