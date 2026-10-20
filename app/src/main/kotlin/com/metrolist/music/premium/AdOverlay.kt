/**
 * BeatPlay blocking ad overlay (observed from the player UI).
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.premium

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.metrolist.music.R

/**
 * Full-screen, non-skippable video ad. The ad itself is the current ExoPlayer
 * queue item, so this PlayerView simply attaches a surface to the player that
 * already owns the ad's audio. The countdown below can be trusted even when the
 * video file is shorter than the configured ad duration.
 */
@Composable
fun AdOverlay(player: Player) {
    val showing by AdState.showing.collectAsStateWithLifecycle()
    if (!showing) return
    val left by AdState.secondsLeft.collectAsStateWithLifecycle()
    val text by AdState.text.collectAsStateWithLifecycle()
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        useController = false
                        keepScreenOn = true
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        this.player = player
                    }
                },
                update = { view -> view.player = player },
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.premium_ad_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFFFFD54F),
                )
                Text(text, style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text(
                    stringResource(R.string.premium_ad_wait, left),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                )
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}
