package app.rngdle.companion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.rngdle.companion.site.AuthState
import app.rngdle.companion.site.PageState
import app.rngdle.companion.site.SiteFailure
import app.rngdle.companion.site.SitePage
import app.rngdle.companion.site.needsSignIn

@Composable
internal fun SiteAuthBar(auth: AuthState, available: Boolean, onSignIn: () -> Unit, onPaste: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("RNGdle", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (available && auth != AuthState.SIGNED_IN && auth != AuthState.COMPLETING) {
                if (auth != AuthState.ENTER_EMAIL && auth != AuthState.OPENING_FORM) {
                    TextButton(onClick = onSignIn) { Text("Войти") }
                }
                TextButton(onClick = onPaste) { Text("Вставить ссылку из письма") }
            }
        }
        val status = when (auth) {
            AuthState.CHECKING, AuthState.SIGNED_OUT -> null
            AuthState.OPENING_FORM -> "Открываем форму входа RNGdle…"
            AuthState.ENTER_EMAIL -> "Введите email в форме RNGdle."
            AuthState.CHECK_MAIL -> "Проверьте почту. Скопируйте ссылку из письма и вернитесь в приложение."
            AuthState.COMPLETING -> "Выполняется вход…"
            AuthState.SIGNED_IN -> "Вы вошли"
            AuthState.FORM_UNAVAILABLE -> "Форма не открылась. Попробуйте снова или выберите Sign In на сайте."
            AuthState.LINK_FAILED -> "Сайт не подтвердил вход. Запросите новую ссылку, если эта уже открыта или истекла."
        }
        if (status != null) {
            Text(status, Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun SitePageOverlay(
    page: SitePage,
    pageState: PageState,
    auth: AuthState,
    onRetry: () -> Unit,
    onCompatibleRendering: () -> Unit,
    onSignIn: () -> Unit,
    onBrowser: () -> Unit,
) {
    when {
        pageState == PageState.Loading -> SitePanel(translucent = true) {
            CircularProgressIndicator()
            Text(if (auth == AuthState.COMPLETING) "Выполняется вход…" else "Загружаем RNGdle…")
        }
        pageState is PageState.Failed -> SitePanel {
            val message = when (pageState.reason) {
                SiteFailure.OFFLINE -> "Не удалось подключиться к RNGdle"
                SiteFailure.HTTP -> "RNGdle вернул ошибку"
                SiteFailure.TLS -> "Не удалось установить защищённое соединение"
                SiteFailure.TIMEOUT -> "Страница RNGdle не отобразилась вовремя"
                SiteFailure.RENDERING -> "WebView загрузил страницу, но не отрисовал её"
                SiteFailure.NAVIGATION -> "Не удалось открыть страницу RNGdle"
                SiteFailure.WEBVIEW -> "Не удалось запустить Android WebView"
            }
            Text(message, style = MaterialTheme.typography.titleLarge)
            if (pageState.reason in listOf(SiteFailure.TIMEOUT, SiteFailure.WEBVIEW)) {
                Text("Проверьте подключение и наличие обновления Android System WebView или Chrome.")
            }
            Button(onClick = onRetry) { Text("Повторить") }
            if (pageState.reason == SiteFailure.RENDERING) {
                TextButton(onClick = onCompatibleRendering) { Text("Включить совместимую отрисовку") }
            }
            TextButton(onClick = onBrowser) { Text("Открыть в браузере") }
        }
        page.needsSignIn(auth) -> SitePanel {
            Text("Войдите, чтобы открыть профиль RNGdle.", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onSignIn) { Text("Войти") }
        }
    }
}

@Composable
private fun SitePanel(translucent: Boolean = false, content: @Composable () -> Unit) {
    val background = MaterialTheme.colorScheme.background
    Surface(Modifier.fillMaxSize(), color = if (translucent) background.copy(alpha = 0.88f) else background) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) { content() }
    }
}

@Composable
internal fun MagicLinkDialog(onDismiss: () -> Unit, onOpen: (String) -> Boolean) {
    var link by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ссылка из письма") },
        text = {
            Column {
                Text("Скопируйте прямую ссылку из письма RNGdle, не открывая её в браузере. Ссылка действует пять минут.")
                OutlinedTextField(
                    value = link,
                    onValueChange = {
                        link = it
                        error = false
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    label = { Text("Ссылка для входа") },
                    singleLine = true,
                    isError = error,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = if (error) {
                        { Text("Проверьте, что это прямая HTTPS-ссылка на rngdle.com и сайт уже открыт в приложении.") }
                    } else null,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = link.isNotBlank(), onClick = { error = !onOpen(link) }) { Text("Открыть") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
