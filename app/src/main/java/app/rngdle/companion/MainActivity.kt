package app.rngdle.companion

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.rngdle.companion.reminder.ReminderScheduler
import app.rngdle.companion.site.AuthState
import app.rngdle.companion.site.PageState
import app.rngdle.companion.site.SiteFailure
import app.rngdle.companion.site.SitePage
import app.rngdle.companion.site.SiteWebView
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model: CompanionViewModel by viewModels()
    private var web by mutableStateOf<SiteWebView?>(null)
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
        createWebView()
        setContent {
            MaterialTheme(colorScheme = companionColors) { CompanionScreen() }
        }
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
        refreshPermissions()
        model.reschedule()
    }

    override fun onPause() {
        web?.persistSession()
        web?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        web?.dispose()
        super.onDestroy()
    }

    private fun createWebView() {
        model.showPageState(PageState.Loading)
        val auth = model.auth.value.takeIf { it == AuthState.CHECK_MAIL } ?: AuthState.CHECKING
        model.showAuthState(auth)
        web = try {
            SiteWebView(this, model.page.value, auth, model::showPageState, model::selectPage, model::showAuthState) {
                if (web === it) web = null
                it.dispose()
            }
        } catch (_: RuntimeException) {
            Log.w("RNGdleWeb", "creation_failed")
            model.showPageState(PageState.Failed(SiteFailure.WEBVIEW))
            null
        }
    }

    private fun openPage(destination: SitePage) {
        model.selectPage(destination)
        val current = web
        if (current == null) createWebView() else current.open(destination)
    }

    private fun retryPage() {
        val current = web
        if (current == null) createWebView() else current.retry()
    }

    private fun pasteEmailLink(): Boolean {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val clip = clipboard.primaryClip ?: return false
        val text = clip.getItemAt(0).text?.toString() ?: return false
        return web?.openMagicLink(text) == true
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
        val auth by model.auth.collectAsStateWithLifecycle()
        val settings by model.reminderSettings.collectAsStateWithLifecycle()
        val update by model.update.collectAsStateWithLifecycle()
        val reminderError by model.reminderError.collectAsStateWithLifecycle()
        var companionSettings by rememberSaveable { mutableStateOf(false) }
        var canGoBack by remember { mutableStateOf(false) }
        var loginDialog by remember { mutableStateOf(false) }
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()

        LaunchedEffect(companionSettings, web) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    val current = web
                    canGoBack = current?.canNavigateBack() == true
                    current?.inspectPage()
                    if (!companionSettings) current?.readVisibleCountdown(model::observeCountdown)
                    delay(650)
                }
            }
        }
        BackHandler(companionSettings || canGoBack) {
            if (companionSettings) companionSettings = false
            else web?.let { if (it.canNavigateBack()) it.goBack() }
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
                                if (!resumeCurrentPage) openPage(destination)
                                canGoBack = web?.canNavigateBack() == true
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
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                if (!companionSettings) {
                    SiteAuthBar(
                        auth = auth,
                        available = web != null,
                        onSignIn = { web?.requestLogin() },
                        onPaste = { if (!pasteEmailLink()) loginDialog = true },
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    web?.let { current ->
                        key(current) {
                            AndroidView(
                                factory = { current },
                                modifier = Modifier.matchParentSize(),
                                update = { view ->
                                    view.visibility = if (companionSettings) android.view.View.INVISIBLE
                                    else android.view.View.VISIBLE
                                },
                            )
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
                    } else {
                        SitePageOverlay(
                            page = page,
                            pageState = webState,
                            auth = auth,
                            onRetry = ::retryPage,
                            onSignIn = { web?.requestLogin() },
                            onBrowser = { SiteWebView.openBrowser(this@MainActivity, Uri.parse(page.url)) },
                        )
                    }
                }
            }
        }
        if (loginDialog) {
            MagicLinkDialog(
                onDismiss = { loginDialog = false },
                onOpen = { link ->
                    (web?.openMagicLink(link) == true).also { accepted -> if (accepted) loginDialog = false }
                },
            )
        }
    }
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
