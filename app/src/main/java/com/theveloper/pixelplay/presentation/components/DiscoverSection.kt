package com.theveloper.pixelplay.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

/** Minimum tiles before the Discover shelf renders. */
val DiscoverSectionMinSongsToShow = 2

private val DiscoverCardSize = 132.dp

private val DiscoverCardShape = AbsoluteSmoothCornerShape(
    cornerRadiusTL = 22.dp,
    smoothnessAsPercentTL = 60,
    cornerRadiusTR = 22.dp,
    smoothnessAsPercentTR = 60,
    cornerRadiusBL = 22.dp,
    smoothnessAsPercentBL = 60,
    cornerRadiusBR = 22.dp,
    smoothnessAsPercentBR = 60
)

/**
 * YouTube Music recommendations on the home screen — a "Discover" shelf
 * fed by the history-radio / home feed / charts blend. Tapping the corner
 * action starts the shelf as one queue; tapping a tile plays it in that
 * context. An empty pool simply hides the shelf.
 */
@Composable
fun DiscoverSection(
    songs: List<Song>,
    playerViewModel: PlayerViewModel,
    currentSongId: String? = null,
    modifier: Modifier = Modifier
) {
    if (songs.size < DiscoverSectionMinSongsToShow) return
    val queueName = stringResource(R.string.home_discover_queue_name)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = stringResource(R.string.home_discover_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.home_discover_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledIconButton(
                modifier = Modifier
                    .height(40.dp)
                    .width(64.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.secondary
                ),
                enabled = songs.isNotEmpty(),
                onClick = {
                    playerViewModel.playSongs(
                        songsToPlay = songs,
                        startSong = songs.first(),
                        queueName = queueName
                    )
                }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyRow(
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(songs, key = { it.id }) { song ->
                DiscoverCard(
                    song = song,
                    isCurrentSong = song.id == currentSongId,
                    onClick = {
                        playerViewModel.showAndPlaySong(
                            song = song,
                            contextSongs = songs,
                            queueName = queueName
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun DiscoverCard(
    song: Song,
    isCurrentSong: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(DiscoverCardSize)
            .clip(DiscoverCardShape)
            .clickable(onClick = onClick)
    ) {
        Card(
            shape = DiscoverCardShape,
            colors = CardDefaults.cardColors(
                containerColor = if (isCurrentSong) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                }
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            SmartImage(
                model = song.albumArtUriString,
                contentDescription = song.title,
                contentScale = ContentScale.Crop,
                shape = DiscoverCardShape,
                targetSize = SmartImageCompactListTargetSize,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DiscoverCardSize)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = song.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = song.displayArtist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
