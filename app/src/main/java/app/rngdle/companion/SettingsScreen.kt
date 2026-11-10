package app.rngdle.companion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.rngdle.companion.reminder.ReminderSettings
import app.rngdle.companion.update.Release
import java.io.File

@Composable
internal fun SettingsScreen(
    settings: ReminderSettings,
    reminderError: Boolean,
    notificationAccess: Boolean,
    exactAccess: Boolean,
    update: UpdateState,
    updateConfigured: Boolean,
    compatibleRendering: Boolean,
    onRenderingChange: (Boolean) -> Unit,
    onChange: ((ReminderSettings) -> ReminderSettings) -> Unit,
    onNotificationAccess: () -> Unit,
    onExactAccess: () -> Unit,
    onSound: () -> Unit,
    onCheck: () -> Unit,
    onDownload: (Release) -> Unit,
    onInstall: (File) -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Настройки приложения", style = MaterialTheme.typography.headlineSmall)
            SettingSwitch("Совместимая отрисовка сайта", compatibleRendering, onRenderingChange)
            Text("Включите, если вкладки сайта отображаются чёрными.")
            HorizontalDivider()
            Text("Напоминания", style = MaterialTheme.typography.titleLarge)
            Text("Новый игровой день начинается в 00:00 UTC.")
            SettingSwitch("Уведомлять о сбросе", settings.enabled) { enabled ->
                onChange { it.copy(enabled = enabled) }
            }
            if (settings.enabled) {
                if (!notificationAccess) {
                    OutlinedButton(onClick = onNotificationAccess) { Text("Разрешить уведомления") }
                }
                Text("Предварительное напоминание")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((minutes, title) in listOf(0 to "Нет", 5 to "5 мин", 15 to "15 мин", 30 to "30 мин")) {
                        FilterChip(
                            selected = minutes == settings.advanceMinutes,
                            onClick = { onChange { it.copy(advanceMinutes = minutes) } },
                            label = { Text(title) },
                        )
                    }
                }
                SettingSwitch("Повторять каждый час", settings.repeat) { repeat ->
                    onChange { it.copy(repeat = repeat) }
                }
                if (settings.repeat) {
                    Text("Повторы прекращаются, когда в приложении появляется таймер после ролла.")
                }
                SettingSwitch("Режим будильника", settings.alarmMode) { enabled ->
                    onChange { it.copy(alarmMode = enabled) }
                }
                if (settings.alarmMode && !exactAccess) {
                    Text("Без специального доступа уведомление может прийти позже.")
                    OutlinedButton(onClick = onExactAccess) { Text("Разрешить точный будильник") }
                }
                Text(if (settings.sound == null) "Звук игры" else "Выбран пользовательский звук")
                Row {
                    OutlinedButton(onClick = onSound) { Text("Выбрать звук") }
                    if (settings.sound != null) {
                        TextButton(onClick = { onChange { it.copy(sound = null) } }) { Text("Сбросить") }
                    }
                }
            }
            if (reminderError) Text("Не удалось сохранить или запланировать напоминание.", color = MaterialTheme.colorScheme.error)
            HorizontalDivider()
            Text("Обновления", style = MaterialTheme.typography.titleLarge)
            Text("Версия ${BuildConfig.VERSION_NAME}")
            Button(
                enabled = updateConfigured && update.canCheck,
                onClick = onCheck,
            ) { Text("Проверить обновления") }
            if (!updateConfigured) Text("Источник GitHub Releases задаётся при сборке подписанной версии.")
            when (update) {
                UpdateState.Idle -> Unit
                UpdateState.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth())
                UpdateState.Current -> Text("Новой версии нет.")
                is UpdateState.Available -> {
                    Text(update.release.title, style = MaterialTheme.typography.titleMedium)
                    if (update.release.changelog.isNotBlank()) Text(update.release.changelog)
                    OutlinedButton(onClick = { onDownload(update.release) }) { Text("Скачать APK") }
                }
                is UpdateState.Downloading -> {
                    Text("Загрузка и проверка APK · ${(update.progress * 100).toInt()}%")
                    LinearProgressIndicator(progress = { update.progress }, modifier = Modifier.fillMaxWidth())
                }
                is UpdateState.Ready -> Button(onClick = { onInstall(update.apk) }) { Text("Установить") }
                is UpdateState.Failed -> {
                    Text("Не удалось получить или проверить обновление.", color = MaterialTheme.colorScheme.error)
                    update.release?.let { release ->
                        OutlinedButton(onClick = { onDownload(release) }) { Text("Повторить загрузку") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
