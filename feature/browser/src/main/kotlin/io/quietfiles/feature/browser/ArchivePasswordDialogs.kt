package io.quietfiles.feature.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.quietfiles.core.designsystem.icon.QfIcons

/** Поле пароля с кнопкой «показать». Пароль не попадает в сохранённое состояние экрана. */
@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, isError: Boolean, onDone: () -> Unit, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) QfIcons.EyeOff else QfIcons.Lock,
                    stringResource(if (visible) R.string.password_hide else R.string.password_show),
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/** Пароль для открытия или распаковки архива (F5.11). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivePasswordDialog(archiveName: String, wrong: Boolean, onDismiss: () -> Unit, onConfirm: (CharArray) -> Unit) {
    var password by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val submit = { if (password.isNotEmpty()) onConfirm(password.toCharArray()) }
    FormSheet(
        title = stringResource(R.string.password_title),
        confirmLabel = stringResource(R.string.password_open),
        confirmEnabled = password.isNotEmpty(),
        onConfirm = submit,
        onDismiss = onDismiss,
    ) {
        Text(stringResource(R.string.password_message, archiveName), style = MaterialTheme.typography.bodyMedium)
        PasswordField(
            value = password,
            onChange = { password = it },
            label = stringResource(R.string.password_label),
            isError = wrong,
            onDone = submit,
            modifier = Modifier.focusRequester(focus),
        )
        if (wrong) Text(stringResource(R.string.password_wrong), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/** Сжатие в zip с паролем по желанию (F5.11): AES-256, пароль вводится дважды. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompressDialog(initial: String, validate: (String) -> String?, onDismiss: () -> Unit, onConfirm: (String, CharArray?) -> Unit) {
    var name by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    var protect by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    val nameError = validate(name.text)
    val mismatch = protect && repeat.isNotEmpty() && repeat != password
    val ready = nameError == null && (!protect || (password.length >= MIN_PASSWORD && password == repeat))
    val submit = { if (ready) onConfirm(name.text, if (protect) password.toCharArray() else null) }
    FormSheet(
        title = stringResource(R.string.dialog_compress),
        confirmLabel = stringResource(R.string.action_compress),
        confirmEnabled = ready,
        onConfirm = submit,
        onDismiss = onDismiss,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.dialog_archive_name)) },
            singleLine = true,
            isError = nameError != null && name.text.isNotEmpty(),
            supportingText = { if (nameError != null && name.text.isNotEmpty()) Text(nameError) },
            suffix = if (!name.text.endsWith(".zip", true)) ({ Text(".zip") }) else null,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { protect = !protect },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = protect, onCheckedChange = null)
            Text(stringResource(R.string.compress_protect), modifier = Modifier.padding(start = 8.dp))
        }
        if (protect) {
            PasswordField(password, { password = it }, stringResource(R.string.password_label), isError = false, onDone = {})
            PasswordField(repeat, { repeat = it }, stringResource(R.string.password_repeat), isError = mismatch, onDone = submit)
            Text(
                if (mismatch) stringResource(R.string.password_mismatch) else pluralStringResource(R.plurals.compress_protect_hint, MIN_PASSWORD, MIN_PASSWORD),
                style = MaterialTheme.typography.bodySmall,
                color = if (mismatch) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Нижний лист с полями: заголовок, содержимое и кнопки, клавиатура не закрывает поля. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormSheet(
    title: String,
    confirmLabel: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Button(onClick = onConfirm, enabled = confirmEnabled) { Text(confirmLabel) }
            }
        }
    }
}

/** Короче шести символов AES защищает плохо: такие пароли подбираются за минуты. */
private const val MIN_PASSWORD = 6
