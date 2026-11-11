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
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.rngdle.companion.reminder.ReminderScheduler
import app.rngdle.companion.reminder.ReminderOptions
import app.rngdle.companion.site.AuthState
import app.rngdle.companion.site.PageState
import app.rngdle.companion.site.SiteFailure
import app.rngdle.companion.site.SitePage
import app.rngdle.companion.site.SiteWebView
import app.rngdle.companion.site.needsSignIn
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model: CompanionViewModel by viewModels()
    private var web by mutableStateOf<SiteWebView?>(null)
    private lateinit var siteHost: FrameLayout
    private lateinit var siteOverlay: ComposeView
    private lateinit var siteHeader: ComposeView
    private var companionSettings by mutableStateOf(false)
    private var loginDialog by mutableStateOf(false)
    private var compatibleRendering by mutableStateOf(false)
    private var exactAccess by mutableStateOf(false)
    private var notificationAccess by mutableStateOf(false)

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshPermissions()
        model.reschedule()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val darkBars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = darkBars, navigationBarStyle = darkBars)
        companionSettings = savedInstanceState?.getBoolean("companionSettings") ?: false
        compatibleRendering = getPreferences(MODE_PRIVATE).getBoolean(COMPATIBLE_RENDERING, false)
        setContentView(createLayout())
        createWebView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("companionSettings", companionSettings)
        super.onSaveInstanceState(outState)
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
            SiteWebView(
                this, model.page.value, auth,
                onState = { model.showPageState(it); updateSiteVisibility() },
                onPage = { model.selectPage(it); updateSiteVisibility() },
                onAuth = { model.showAuthState(it); updateSiteVisibility() },
                onRendererGone = {
                    if (web === it) web = null
                    it.dispose()
                },
            ).apply {
                setCompatibleRendering(compatibleRendering)
                siteHost.addView(this, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
        } catch (_: RuntimeException) {
            Log.w("RNGdleWeb", "creation_failed")
            model.showPageState(PageState.Failed(SiteFailure.WEBVIEW))
            null
        }
        updateSiteVisibility()
    }

    private fun createLayout(): View {
        siteHeader = composeView(R.id.site_header) {
            val auth by model.auth.collectAsStateWithLifecycle()
            Surface(color = MaterialTheme.colorScheme.background) {
                SiteAuthBar(
                    auth = auth,
                    available = web != null,
                    onSignIn = { web?.requestLogin() },
                    onPaste = { if (!pasteEmailLink()) loginDialog = true },
                )
            }
        }
        siteOverlay = composeView(R.id.site_overlay) { SiteOverlay() }.apply { isClickable = true }
        siteHost = FrameLayout(this).apply {
            addView(siteOverlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xff121117.toInt())
            addView(siteHeader, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(siteHost, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(composeView(R.id.site_navigation) { NavigationAndLogin() },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
                view.setPadding(safe.left, safe.top, safe.right, maxOf(safe.bottom, keyboard.bottom))
                WindowInsetsCompat.CONSUMED
            }
        }
    }

    private fun composeView(viewId: Int, content: @Composable () -> Unit) = ComposeView(this).apply {
        id = viewId
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { MaterialTheme(colorScheme = companionColors) { content() } }
    }

    private fun updateSiteVisibility() {
        val covered = companionSettings || model.webState.value != PageState.Ready ||
            model.page.value.needsSignIn(model.auth.value)
        siteHeader.visibility = if (companionSettings) View.GONE else View.VISIBLE
        siteOverlay.visibility = if (covered) View.VISIBLE else View.GONE
        web?.apply {
            importantForAccessibility = if (covered) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            showSite(!companionSettings)
        }
    }

    private fun showSettings(show: Boolean) {
        companionSettings = show
        updateSiteVisibility()
    }

    private fun changeRendering(enabled: Boolean) {
        compatibleRendering = enabled
        getPreferences(MODE_PRIVATE).edit().putBoolean(COMPATIBLE_RENDERING, enabled).apply()
        web?.setCompatibleRendering(enabled)
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
    private fun NavigationAndLogin() {
        val page by model.page.collectAsStateWithLifecycle()
        val reminderOptions by model.reminderOptions.collectAsStateWithLifecycle()
        var canGoBack by remember { mutableStateOf(false) }

        LaunchedEffect(reminderOptions?.count, notificationAccess) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                if ((reminderOptions?.count ?: 0) > 0 && Build.VERSION.SDK_INT >= 33 && !notificationAccess &&
                    !getPreferences(MODE_PRIVATE).getBoolean("asked_notifications", false)
                ) requestNotifications()
            }
        }

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
            if (companionSettings) showSettings(false)
            else web?.let { if (it.canNavigateBack()) it.goBack() }
        }

        NavigationBar(windowInsets = WindowInsets(0, 0, 0, 0)) {
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
                        if (!resumeCurrentPage) openPage(destination)
                        showSettings(false)
                        canGoBack = web?.canNavigateBack() == true
                    },
                    icon = { Icon(icon, contentDescription = null) },
                    label = { Text(label, maxLines = 1) },
                )
            }
            NavigationBarItem(
                selected = companionSettings,
                onClick = { showSettings(true) },
                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                label = { Text("Настройки", maxLines = 1) },
            )
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

    @Composable
    private fun SiteOverlay() {
        val page by model.page.collectAsStateWithLifecycle()
        val webState by model.webState.collectAsStateWithLifecycle()
        val auth by model.auth.collectAsStateWithLifecycle()
        val savedOptions by model.reminderOptions.collectAsStateWithLifecycle()
        val reminderOptions = savedOptions ?: ReminderOptions()
        val update by model.update.collectAsStateWithLifecycle()
        val reminderError by model.reminderError.collectAsStateWithLifecycle()
        if (companionSettings) {
            val snackbar = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            Box(Modifier.fillMaxSize()) {
                SettingsScreen(
                    reminderOptions = reminderOptions,
                    reminderError = reminderError,
                    notificationAccess = notificationAccess,
                    exactAccess = exactAccess,
                    update = update,
                    updateConfigured = model.updateConfigured,
                    compatibleRendering = compatibleRendering,
                    onRenderingChange = ::changeRendering,
                    onReminderCountChange = model::adjustReminderCount,
                    onAlarmModeChange = { enabled ->
                        model.setAlarmMode(enabled)
                        if (enabled && reminderOptions.count > 0 && !exactAccess) requestExactAlarm()
                    },
                    onNotificationAccess = ::requestNotifications,
                    onExactAccess = ::requestExactAlarm,
                    onCheck = model::checkUpdates,
                    onDownload = model::downloadUpdate,
                    onInstall = { apk ->
                        installUpdate(apk)?.let { message -> scope.launch { snackbar.showSnackbar(message) } }
                    },
                )
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
        } else {
            SitePageOverlay(
                page = page,
                pageState = webState,
                auth = auth,
                onRetry = ::retryPage,
                onCompatibleRendering = {
                    changeRendering(true)
                    retryPage()
                },
                onSignIn = { web?.requestLogin() },
                onBrowser = { SiteWebView.openBrowser(this@MainActivity, Uri.parse(page.url)) },
            )
        }
    }

    private companion object {
        const val COMPATIBLE_RENDERING = "compatible_site_rendering"
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
