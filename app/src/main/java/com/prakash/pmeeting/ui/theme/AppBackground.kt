package com.prakash.pmeeting.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.prakash.pmeeting.R

/**
 * Full-bleed app wallpaper.
 *
 * Two 1440x2560 portraits share the same geometry (see `design/`): a navy night
 * variant for the dark theme and an airy blue day variant for the light theme.
 * Both keep their top and bottom thirds quiet and their centre flat, so app bars,
 * navigation and list content stay readable.
 *
 * A light scrim is layered on top so text and controls remain legible on any
 * screen crop without flattening the artwork. Screens opt in by using a
 * transparent `Scaffold`; opaque surfaces such as app bars and cards still sit
 * above the artwork and keep their own contrast.
 */
@Composable
fun AppBackground(dark: Boolean, modifier: Modifier = Modifier) {
    val scrim = if (dark) {
        Brush.verticalGradient(
            0.00f to Color(0x47040A14),
            0.30f to Color(0x2B040A14),
            0.62f to Color(0x3D040A14),
            1.00f to Color(0x6B040A14)
        )
    } else {
        Brush.verticalGradient(
            0.00f to Color(0x33FFFFFF),
            0.30f to Color(0x14FFFFFF),
            0.62f to Color(0x0DFFFFFF),
            1.00f to Color(0x4DFFFFFF)
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Image(
            painter = painterResource(if (dark) R.drawable.bg_app else R.drawable.bg_app_light),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(scrim)
        )
    }
}
