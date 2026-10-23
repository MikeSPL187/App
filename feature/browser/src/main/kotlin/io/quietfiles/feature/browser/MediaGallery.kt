package io.quietfiles.feature.browser

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.quietfiles.core.designsystem.component.SelectionMark
import io.quietfiles.core.designsystem.icon.QfIcons
import io.quietfiles.core.designsystem.theme.QfTheme
import io.quietfiles.core.model.FileCategory
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Откуда фото или видео: фильтры галереи (F7.2). Определяется по пути. */
enum class MediaSource { ALL, CAMERA, SCREENSHOTS, MESSENGERS, DOWNLOADS }

private val messengerFolders = listOf("telegram", "whatsapp", "viber", "vk", "signal", "messenger")

internal fun mediaSourceOf(path: String): MediaSource {
    val segments = path.lowercase().split('/')
    return when {
        segments.any { it == "screenshots" || it == "screenshot" } -> MediaSource.SCREENSHOTS
        segments.any { seg -> messengerFolders.any { seg == it || seg.startsWith("$it ") } } -> MediaSource.MESSENGERS
        "dcim" in segments -> MediaSource.CAMERA
        "download" in segments || "downloads" in segments -> MediaSource.DOWNLOADS
        else -> MediaSource.ALL
    }
}

/** Заголовок группы: сегодня, вчера или месяц. */
sealed interface DateBucket {
    data object Today : DateBucket
    data object Yesterday : DateBucket
    data class Month(val month: YearMonth) : DateBucket
}

/** Группирует по датам изменения, новые сверху. */
internal fun groupByDate(items: List<BrowserItem>, today: LocalDate, zone: ZoneId): List<Pair<DateBucket, List<BrowserItem>>> =
    items
        .sortedByDescending { it.entry.lastModified }
        .groupBy { item ->
            val date = Instant.ofEpochMilli(item.entry.lastModified).atZone(zone).toLocalDate()
            when (date) {
                today -> DateBucket.Today
                today.minusDays(1) -> DateBucket.Yesterday
                else -> DateBucket.Month(YearMonth.from(date))
            }
        }
        .toList()

/** Сетка фото или видео с фильтрами по источнику и заголовками по датам (макет 06). */
@Composable
internal fun MediaGallery(
    state: BrowserUiState,
    onOpen: (BrowserItem) -> Unit,
    onLongPress: (BrowserItem) -> Unit,
) {
    var source by remember(state.location) { mutableStateOf(MediaSource.ALL) }
    val bySource = remember(state.items) { state.items.groupBy { mediaSourceOf(it.entry.path) } }
    val available = remember(bySource) { MediaSource.entries.filter { it == MediaSource.ALL || bySource.containsKey(it) } }
    val shown = if (source == MediaSource.ALL) state.items else bySource[source].orEmpty()
    val zone = remember { ZoneId.systemDefault() }
    val groups = remember(shown) { groupByDate(shown, LocalDate.now(zone), zone) }
    val locale = LocalConfiguration.current.locales[0]

    Column(Modifier.fillMaxSize()) {
        if (available.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(available) { s ->
                    FilterChip(
                        selected = source == s,
                        onClick = { source = s },
                        label = { Text(sourceLabel(s)) },
                    )
                }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            groups.forEach { (bucket, items) ->
                item(key = "h-$bucket", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                    Text(
                        bucketTitle(bucket, locale),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp),
                    )
                }
                items(items, key = { it.entry.path }, contentType = { "media" }) { item ->
                    MediaCell(
                        item = item,
                        selectionMode = state.selectionMode,
                        selected = item.entry.path in state.selection,
                        onClick = { onOpen(item) },
                        onLongClick = { onLongPress(item) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaCell(
    item: BrowserItem,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val entry = item.entry
    val colors = QfTheme.extended.of(entry.category)
    var failed by remember(entry.path) { mutableStateOf(false) }
    val shape = RoundedCornerShape(if (selected) 14.dp else 6.dp)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .padding(if (selected) 6.dp else 0.dp)
            .clip(shape)
            .background(colors.container)
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics {
                contentDescription = entry.name
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        if (failed) {
            Icon(QfIcons.forCategory(entry.category), null, tint = colors.onContainer, modifier = Modifier.size(32.dp))
        } else {
            AsyncImage(
                model = File(entry.path),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (entry.category == FileCategory.VIDEO) {
            Icon(
                QfIcons.Play,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .size(20.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
                    .padding(3.dp),
            )
        }
        if (selectionMode) {
            SelectionMark(selected, Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
    }
}

@Composable
private fun sourceLabel(source: MediaSource): String = stringResource(
    when (source) {
        MediaSource.ALL -> R.string.media_all
        MediaSource.CAMERA -> R.string.media_camera
        MediaSource.SCREENSHOTS -> R.string.media_screenshots
        MediaSource.MESSENGERS -> R.string.media_messengers
        MediaSource.DOWNLOADS -> R.string.media_downloads
    },
)

@Composable
private fun bucketTitle(bucket: DateBucket, locale: Locale): String = when (bucket) {
    DateBucket.Today -> stringResource(R.string.media_today)
    DateBucket.Yesterday -> stringResource(R.string.media_yesterday)
    is DateBucket.Month -> {
        val pattern = if (bucket.month.year == YearMonth.now().year) "LLLL" else "LLLL yyyy"
        DateTimeFormatter.ofPattern(pattern, locale).format(bucket.month)
            .replaceFirstChar { it.titlecase(locale) }
    }
}
