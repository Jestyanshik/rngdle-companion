package app.rngdle.companion

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.rngdle.companion.reminder.ReminderPolicy
import app.rngdle.companion.reminder.ReminderOptions
import app.rngdle.companion.reminder.ReminderRepository
import app.rngdle.companion.reminder.ReminderScheduler
import app.rngdle.companion.site.AuthState
import app.rngdle.companion.site.PageState
import app.rngdle.companion.site.SitePage
import app.rngdle.companion.update.GitHubReleaseRepository
import app.rngdle.companion.update.Release
import app.rngdle.companion.update.ReleasePolicy
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CompanionViewModel(application: Application) : AndroidViewModel(application) {
    private val reminders = ReminderRepository(application)
    private val scheduler = ReminderScheduler(application)
    private val releases = GitHubReleaseRepository(application)
    private var observedRollDay: Long? = null

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
    val reminderOptions = reminders.options.stateIn<ReminderOptions?>(viewModelScope, SharingStarted.Eagerly, null)

    fun selectPage(page: SitePage) {
        mutablePage.value = page
    }

    fun showPageState(state: PageState) {
        mutableWebState.value = state
    }

    fun showAuthState(state: AuthState) {
        mutableAuth.value = state
    }

    fun setAlarmMode(enabled: Boolean) = reminderAction {
        reminders.setAlarmMode(enabled)
        scheduler.schedule()
    }

    fun adjustReminderCount(delta: Int) = reminderAction {
        reminders.adjustCount(delta)
        scheduler.schedule()
    }

    fun reschedule() = reminderAction { scheduler.schedule() }

    suspend fun postTestNotification(): Boolean = scheduler.postTestNotification(reminders.options.first().alarmMode)

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
        val day = ReminderPolicy.confirmedRollDay(value, Instant.now()) ?: return
        if (observedRollDay == day) return
        observedRollDay = day
        reminderAction {
            try {
                scheduler.recordRoll(day)
            } catch (error: Exception) {
                if (observedRollDay == day) observedRollDay = null
                throw error
            }
        }
    }

    fun needsRollObservation(day: Long): Boolean = observedRollDay != day

    fun checkUpdates() {
        loadReleases { releases.check()?.let { UpdateState.Available(it) } ?: UpdateState.Current }
    }

    fun browseVersions() {
        loadReleases { UpdateState.History(releases.history()) }
    }

    private fun loadReleases(load: suspend () -> UpdateState) {
        if (!updateConfigured || !mutableUpdate.value.canCheck) return
        mutableUpdate.value = UpdateState.Checking
        viewModelScope.launch {
            mutableUpdate.value = try {
                load()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                UpdateState.Failed()
            }
        }
    }

    fun selectVersion(release: Release) {
        val history = mutableUpdate.value as? UpdateState.History ?: return
        if (release in history.releases && release.versionCode != BuildConfig.VERSION_CODE.toLong()) {
            mutableUpdate.value = UpdateState.Available(release)
        }
    }

    fun downloadUpdate(release: Release) {
        if (mutableUpdate.value.releaseToDownload != release) return
        mutableUpdate.value = UpdateState.Downloading(0f)
        viewModelScope.launch {
            mutableUpdate.value = try {
                val apk = releases.download(release) { mutableUpdate.value = UpdateState.Downloading(it) }
                UpdateState.Ready(release, apk)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                UpdateState.Failed(release)
            }
        }
    }

    fun beginArchiveSave(): String? {
        val download = mutableUpdate.value as? UpdateState.Ready ?: return null
        if (!ReleasePolicy.isRollback(BuildConfig.VERSION_CODE.toLong(), download.release.versionCode)) return null
        mutableUpdate.value = UpdateState.SavingArchive(download)
        return download.release.fileName
    }

    fun finishArchiveSave(destination: Uri?): Boolean {
        val saving = mutableUpdate.value as? UpdateState.SavingArchive ?: return destination == null
        val download = saving.download
        if (destination == null) {
            mutableUpdate.value = download
            return true
        }
        viewModelScope.launch {
            val result = try {
                releases.saveArchive(download.release, download.apk, destination)
                ArchiveSave.SAVED
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                ArchiveSave.FAILED
            }
            mutableUpdate.value = download.copy(archiveSave = result)
        }
        return true
    }
}
