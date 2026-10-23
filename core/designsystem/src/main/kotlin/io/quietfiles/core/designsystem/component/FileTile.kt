package io.quietfiles.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.quietfiles.core.designsystem.icon.QfIcons
import io.quietfiles.core.designsystem.theme.QfTheme
import io.quietfiles.core.model.FileCategory
import io.quietfiles.core.model.network.NetworkPaths
import java.io.File

/**
 * Плитка файла: тональный квадрат с цветом категории или миниатюра для фото и видео.
 * Если миниатюру получить не удалось, остаётся значок категории.
 */
@Composable
fun FileTile(
    category: FileCategory,
    modifier: Modifier = Modifier,
    path: String? = null,
    size: Dp = 40.dp,
    cornerRadius: Dp = 12.dp,
    showThumbnail: Boolean = true,
) {
    val colors = QfTheme.extended.of(category)
    val shape = RoundedCornerShape(cornerRadius)
    // Миниатюры с сервера не строим: каждая — это загрузка файла целиком по сети.
    val wantsThumbnail = showThumbnail && path != null && !NetworkPaths.isNetwork(path) &&
        (category == FileCategory.IMAGE || category == FileCategory.VIDEO)
    var thumbnailFailed by remember(path) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(colors.container),
        contentAlignment = Alignment.Center,
    ) {
        if (!wantsThumbnail || thumbnailFailed) {
            Icon(
                imageVector = QfIcons.forCategory(category),
                contentDescription = null,
                tint = colors.onContainer,
                modifier = Modifier.size(size * 0.55f),
            )
        }
        if (wantsThumbnail && !thumbnailFailed) {
            AsyncImage(
                model = File(requireNotNull(path)),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { thumbnailFailed = true },
                modifier = Modifier.size(size),
            )
            if (category == FileCategory.VIDEO && size >= 40.dp) {
                Icon(
                    imageVector = QfIcons.Play,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier
                        .size(size * 0.36f)
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
                        .align(Alignment.Center),
                )
            }
        }
    }
}
