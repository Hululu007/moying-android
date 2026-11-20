package com.moying.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource

/** The launcher's central 72dp viewport, using the exact same vector and colours. */
@Composable
internal fun BrandIcon(modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(Theme.Radius.Control))
        .background(colorResource(R.color.launcher_background))) {
        Image(
            painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null, // The adjacent brand name already supplies its label.
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = 1.5f
                scaleY = 1.5f
            },
        )
    }
}
