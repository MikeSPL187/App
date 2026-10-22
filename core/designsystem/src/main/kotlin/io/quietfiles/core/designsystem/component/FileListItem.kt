package io.quietfiles.core.designsystem.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.quietfiles.core.designsystem.R
import io.quietfiles.core.designsystem.icon.QfIcons
import io.quietfiles.core.model.FileCategory

/**
 * Строка файла (макет 02): плитка 40 dp, имя, «тип · размер · дата», метка «Новый», действия.
 * В режиме выбора справа круг-отметка, выделение показано и цветом, и отметкой.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileListItem(
    name: String,
    meta: String,
    category: FileCategory,
    path: String?,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    isNew: Boolean = false,
    dimmed: Boolean = false,
    compact: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onMoreClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button)
            .semantics { this.selected = selected }
            .heightIn(min = if (compact) 56.dp else 64.dp)
            .padding(start = 12.dp, end = 4.dp)
            .alpha(if (dimmed) 0.6f else 1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FileTile(category = category, path = path, size = if (compact) 36.dp else 40.dp)
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) colors.onSecondaryContainer else colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isNew) NewBadge()
            }
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            selectionMode -> SelectionMark(selected = selected, modifier = Modifier.padding(end = 12.dp))
            trailing != null -> trailing()
            onMoreClick != null -> IconButton(onClick = onMoreClick) {
                Icon(QfIcons.More, contentDescription = stringResource(R.string.ds_file_actions), tint = colors.onSurfaceVariant)
            }
            else -> Spacer(Modifier.width(12.dp))
        }
    }
}

@Composable
fun NewBadge(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.ds_new_badge),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(9.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** Круг выбора: пустой контур или заполненный круг с галочкой. */
@Composable
fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (selected) colors.primary else Color.Transparent)
            .then(if (selected) Modifier else Modifier.border(2.dp, colors.outline, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(QfIcons.Check, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(14.dp))
        }
    }
}

/** Ячейка сетки: квадратная миниатюра или плитка категории и подпись. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGridItem(
    name: String,
    category: FileCategory,
    path: String?,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { this.selected = selected }
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            FileTileFill(category = category, path = path)
            if (selectionMode) {
                SelectionMark(
                    selected = selected,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(colors.surface.copy(alpha = if (selected) 0f else 0.7f), CircleShape),
                )
            }
        }
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FileTileFill(category: FileCategory, path: String?) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f)) {
        FileTile(category = category, path = path, size = maxWidth, cornerRadius = 14.dp)
    }
}
