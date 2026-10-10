package com.ondumusic.player.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.ondumusic.player.R

/** The launcher icon's layers, drawn directly: Compose can't paint an adaptive icon resource. */
@Composable
fun AppIcon(size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.25f))) {
        Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null, Modifier.fillMaxSize())
        // The foreground is drawn for a 108dp canvas whose outer sixth is masked away.
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, Modifier.fillMaxSize().scale(1.5f))
    }
}
