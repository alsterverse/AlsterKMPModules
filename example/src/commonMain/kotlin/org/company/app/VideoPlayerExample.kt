package org.company.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import se.alster.kmp.media.AspectRatio
import se.alster.kmp.media.player.PlayerView
import se.alster.kmp.media.player.Track
import se.alster.kmp.media.player.TrackList
import se.alster.kmp.media.player.rememberPlayer
import se.alster.kmp.storage.FilePath
import se.alster.kmp.storage.Location

@Composable
fun VideoPlayerExample() {
    val player = rememberPlayer()

    PlayerView(
        modifier = Modifier.fillMaxSize(),
        player = player,
        aspectRatio = AspectRatio.FitWithAspectRatio,
        enableMediaControls = true
    )
    DisposableEffect(Unit) {
        player.prepareTrackListForPlayback(
            TrackList(
                listOf(
                    Track.File(FilePath("video.mp4", Location.Documents)),
                    Track.File(FilePath("video.mp4", Location.Documents)),
                )
            )
        )
        player.setPlayOnReady(true)
        onDispose {
            player.release()
        }
    }
}
