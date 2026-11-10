package app.rngdle.companion

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.rngdle.companion.reminder.ReminderPolicy
import app.rngdle.companion.reminder.ReminderRepository
import app.rngdle.companion.reminder.ReminderScheduler
import app.rngdle.companion.reminder.ReminderSettings
import app.rngdle.companion.site.AuthState
import app.rngdle.companion.site.PageState
import app.rngdle.companion.site.SitePage
import app.rngdle.companion.update.GitHubReleaseRepository
import app.rngdle.companion.update.Release
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CompanionViewModel(application: Application) : AndroidViewModel(application) {
    private val reminders = ReminderRepository(application)
    private val scheduler = ReminderScheduler(application)
    private val releases = GitHubReleaseRepository(application)
    private var seenDay: Long? = null

    private val mutablePage = MutableStateFlow(SitePage.PLAY)
    val page = mutablePage.asStateFlow()
    private val mutableWebState = MutableStateFlow<PageState>(PageState.Loading)
    val webState = mutableWebState.asStateFlow()
    private val mutableAuth = MutableStateFlow(AuthState.CHECKING)
    val auth = mutableAuth.asStateFlow()
    private val mutableUpdate = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update = mutableUpdate.asStateFlow()
    private val mutableReminderError = MutableStateFlow(false)
    val reminderError = mutableReminderError.asStateFlow()
    val updateConfigured = releases.configured
    val reminderSettings = reminders.settings.stateIn(viewModelScope, SharingStarted.Eagerly, ReminderSettings())

    fun selectPage(page: SitePage) {
        mutablePage.value = page
    }

    fun showPageState(state: PageState) {
        mutableWebState.value = state
    }

    fun showAuthState(state: AuthState) {
        mutableAuth.value = state
    }

    fun changeReminder(change: (ReminderSettings) -> ReminderSettings) = reminderAction {
        reminders.change(change)
        if (!reminders.snapshot().first.enabled) scheduler.clearNotification()
        scheduler.schedule()
    }

    fun reschedule() = reminderAction { scheduler.schedule() }

    private fun reminderAction(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
                mutableReminderError.value = false
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableReminderError.value = true
            }
        }
    }

    fun observeCountdown(value: String) {
        val now = Instant.now()
        val day = ReminderPolicy.day(now)
        if (seenDay == day || !ReminderPolicy.matchesCountdown(value, now)) return
        seenDay = day
        reminderAction {
            try {
                reminders.observeRoll(day)
            } catch (error: Exception) {
                seenDay = null
                throw error
            }
            scheduler.clearNotification()
        }
    }

    fun checkUpdates() {
        if (!updateConfigured || !mutableUpdate.value.canCheck) return
        mutableUpdate.value = UpdateState.Checking
        viewModelScope.launch {
            mutableUpdate.value = try {
                releases.check()?.let { UpdateState.Available(it) } ?: UpdateState.Current
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                UpdateState.Failed()
            }
        }
    }

    fun downloadUpdate(release: Release) {
        if (mutableUpdate.value.releaseToDownload != release) return
        mutableUpdate.value = UpdateState.Downloading(0f)
        viewModelScope.launch {
            mutableUpdate.value = try {
                val apk = releases.download(release) { mutableUpdate.value = UpdateState.Downloading(it) }
                UpdateState.Ready(apk)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                UpdateState.Failed(release)
            }
        }
    }
}
