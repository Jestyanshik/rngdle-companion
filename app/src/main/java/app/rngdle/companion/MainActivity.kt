package app.rngdle.companion

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.rngdle.companion.reminder.ReminderScheduler
import app.rngdle.companion.site.PageState
import app.rngdle.companion.site.SitePage
import app.rngdle.companion.site.SiteWebView
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model: CompanionViewModel by viewModels()
    private lateinit var web: SiteWebView
    private var exactAccess by mutableStateOf(false)
    private var notificationAccess by mutableStateOf(false)

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshPermissions()
        model.reschedule()
    }

    private val soundPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { sound ->
        if (sound != null) {
            try {
                contentResolver.takePersistableUriPermission(sound, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                model.changeReminder { it.copy(sound = sound.toString()) }
            } catch (_: SecurityException) {
                Toast.makeText(this, "Android не предоставил постоянный доступ к этому файлу.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        web = SiteWebView(this, model::showPageState, model::selectPage)
        web.open(model.page.value)
        setContent {
            MaterialTheme(colorScheme = companionColors) { CompanionScreen() }
        }
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        refreshPermissions()
        model.reschedule()
    }

    override fun onPause() {
        web.persistSession()
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        val parent = web.parent as? android.view.ViewGroup
        parent?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    private fun refreshPermissions() {
        exactAccess = ReminderScheduler(this).hasExactAccess()
        val manager = getSystemService(android.app.NotificationManager::class.java)
        notificationAccess = manager.areNotificationsEnabled()
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED &&
            (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) ||
                !getPreferences(MODE_PRIVATE).getBoolean("asked_notifications", false))
        ) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("asked_notifications", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    private fun requestExactAlarm() {
        if (Build.VERSION.SDK_INT >= 31 && !exactAccess) {
            openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }
    }

    private fun openSettings(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    private fun installUpdate(file: File): String? {
        if (!file.isFile) return "Файл удалён системой. Проверьте обновления и скачайте его снова."
        if (!packageManager.canRequestPackageInstalls()) {
            return if (openSettings(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))) {
                "Разрешите установку из этого приложения, затем снова нажмите «Установить»."
            } else {
                "Системный экран разрешения установки недоступен."
            }
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.updates", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return if (openSettings(intent)) null else "Системный установщик APK недоступен."
    }

    @Composable
    private fun CompanionScreen() {
        val page by model.page.collectAsStateWithLifecycle()
        val webState by model.webState.collectAsStateWithLifecycle()
        val settings by model.reminderSettings.collectAsStateWithLifecycle()
        val update by model.update.collectAsStateWithLifecycle()
        val reminderError by model.reminderError.collectAsStateWithLifecycle()
        var companionSettings by rememberSaveable { mutableStateOf(false) }
        var canGoBack by remember { mutableStateOf(false) }
        var loginDialog by remember { mutableStateOf(false) }
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()

        LaunchedEffect(companionSettings, webState) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (!companionSettings) {
                    canGoBack = web.canGoBack()
                    if (webState == PageState.Ready) web.readVisibleCountdown(model::observeCountdown)
                    delay(1_500)
                }
            }
        }
        BackHandler(companionSettings || canGoBack) {
            if (companionSettings) companionSettings = false
            else if (web.canGoBack()) web.goBack()
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar {
                    SitePage.entries.forEach { destination ->
                        val (label, icon) = when (destination) {
                            SitePage.PLAY -> "Игра" to Icons.Default.Home
                            SitePage.RANKINGS -> "Рейтинг" to Icons.Default.Star
                            SitePage.FRIENDS -> "Друзья" to Icons.Default.Face
                            SitePage.PROFILE -> "Профиль" to Icons.Default.Person
                        }
                        NavigationBarItem(
                            selected = !companionSettings && page == destination,
                            onClick = {
                                val resumeCurrentPage = companionSettings && page == destination
                                companionSettings = false
                                if (!resumeCurrentPage) web.open(destination)
                                canGoBack = web.canGoBack()
                            },
                            icon = { Icon(icon, contentDescription = null) },
                            label = { Text(label, maxLines = 1) },
                        )
                    }
                    NavigationBarItem(
                        selected = companionSettings,
                        onClick = { companionSettings = true },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("Настройки", maxLines = 1) },
                    )
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (!companionSettings) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("RNGdle Companion", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { loginDialog = true }) { Text("Ссылка для входа") }
                    }
                    if (webState == PageState.Loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Box(Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { web },
                        modifier = Modifier.fillMaxSize(),
                        update = { it.visibility = if (companionSettings || webState in listOf(PageState.Failed, PageState.Offline)) View.INVISIBLE else View.VISIBLE },
                    )
                    if (!companionSettings && webState in listOf(PageState.Failed, PageState.Offline)) {
                        Column(Modifier.align(Alignment.Center).padding(24.dp)) {
                            Text(
                                if (webState == PageState.Offline) "Не удалось подключиться к RNGdle"
                                else "RNGdle временно недоступен",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text("Проверьте подключение и повторите попытку.")
                            Button(onClick = web::retry, modifier = Modifier.padding(top = 12.dp)) { Text("Повторить") }
                            TextButton(onClick = { SiteWebView.openBrowser(this@MainActivity, Uri.parse(page.url)) }) {
                                Text("Открыть в браузере")
                            }
                        }
                    }
                    if (companionSettings) {
                        SettingsScreen(
                            settings = settings,
                            reminderError = reminderError,
                            notificationAccess = notificationAccess,
                            exactAccess = exactAccess,
                            update = update,
                            updateConfigured = model.updateConfigured,
                            onChange = { model.changeReminder(it) },
                            onNotificationAccess = ::requestNotifications,
                            onExactAccess = ::requestExactAlarm,
                            onSound = { soundPicker.launch(arrayOf("audio/*")) },
                            onCheck = model::checkUpdates,
                            onDownload = model::downloadUpdate,
                            onInstall = { apk ->
                                installUpdate(apk)?.let { message -> scope.launch { snackbar.showSnackbar(message) } }
                            },
                        )
                    }
                }
            }
        }
        if (loginDialog) {
            MagicLinkDialog(
                onDismiss = { loginDialog = false },
                onOpen = { link ->
                    web.openMagicLink(link).also { accepted -> if (accepted) loginDialog = false }
                },
            )
        }
    }
}

@Composable
private fun MagicLinkDialog(onDismiss: () -> Unit, onOpen: (String) -> Boolean) {
    var link by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Вход в RNGdle") },
        text = {
            Column {
                Text("Сначала запросите ссылку через Sign up или Sign in на сайте внутри приложения. Скопируйте прямую ссылку из письма и вставьте её сюда в течение пяти минут.")
                OutlinedTextField(
                    value = link,
                    onValueChange = {
                        link = it
                        error = false
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    label = { Text("Ссылка из письма") },
                    singleLine = true,
                    isError = error,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = if (error) {
                        { Text("Нужна прямая HTTPS-ссылка на rngdle.com.") }
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

private val companionColors = darkColorScheme(
    background = Color(0xFF121117),
    surface = Color(0xFF232229),
    surfaceContainer = Color(0xFF211E29),
    primary = Color(0xFFE99AFF),
    onPrimary = Color(0xFF3B0849),
    secondaryContainer = Color(0xFF422651),
    onSecondaryContainer = Color(0xFFFFD7FF),
)
