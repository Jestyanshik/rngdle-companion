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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.rngdle.companion.reminder.ReminderOptions
import app.rngdle.companion.update.Release
import java.io.File

@Composable
internal fun SettingsScreen(
    reminderOptions: ReminderOptions,
    reminderError: Boolean,
    notificationAccess: Boolean,
    exactAccess: Boolean,
    update: UpdateState,
    updateConfigured: Boolean,
    compatibleRendering: Boolean,
    onRenderingChange: (Boolean) -> Unit,
    onReminderCountChange: (Int) -> Unit,
    onAlarmModeChange: (Boolean) -> Unit,
    onTestNotification: () -> Unit,
    onNotificationAccess: () -> Unit,
    onExactAccess: () -> Unit,
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
            Text("В случайное время круглые сутки. После ролла напоминания за игровой день отменяются.")
            val count = reminderOptions.count
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Напоминаний в день", Modifier.weight(1f))
                TextButton(
                    enabled = count > 0,
                    onClick = { onReminderCountChange(-1) },
                    modifier = Modifier.semantics { contentDescription = "Уменьшить число напоминаний" },
                ) { Text("−") }
                Text(count.toString())
                TextButton(
                    enabled = count < ReminderOptions.MAX_COUNT,
                    onClick = { onReminderCountChange(1) },
                    modifier = Modifier.semantics { contentDescription = "Увеличить число напоминаний" },
                ) { Text("+") }
            }
            SettingSwitch("Будильник", reminderOptions.alarmMode, onAlarmModeChange)
            OutlinedButton(onClick = onTestNotification) { Text("Проверить уведомление") }
            if (count > 0 && !notificationAccess) {
                OutlinedButton(onClick = onNotificationAccess) { Text("Разрешить уведомления Android") }
            }
            if (count > 0 && reminderOptions.alarmMode && !exactAccess) {
                OutlinedButton(onClick = onExactAccess) { Text("Разрешить точный будильник Android") }
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
