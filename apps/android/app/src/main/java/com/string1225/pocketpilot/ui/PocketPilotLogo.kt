package com.string1225.pocketpilot.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.string1225.pocketpilot.R

/** Shared PocketPilot brand mark used by onboarding and the settings entry point. */
@Composable
fun PocketPilotLogo(
    modifier: Modifier = Modifier.size(40.dp),
    contentDescription: String? = null,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 28),
        color = colorResource(R.color.pocketpilot_icon_background),
    ) {
        Image(
            painter = painterResource(R.drawable.pocketpilot_logo_foreground),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(2.dp)
                .graphicsLayer(scaleX = 1.28f, scaleY = 1.28f),
        )
    }
}
