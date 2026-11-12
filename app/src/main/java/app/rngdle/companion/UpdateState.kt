package app.rngdle.companion

import app.rngdle.companion.update.Release
import java.io.File

enum class ArchiveSave { NOT_SAVED, SAVED, FAILED }

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object Current : UpdateState
    data class History(val releases: List<Release>) : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data class Ready(val release: Release, val apk: File, val archiveSave: ArchiveSave = ArchiveSave.NOT_SAVED) : UpdateState
    data class SavingArchive(val download: Ready) : UpdateState
    data class Failed(val release: Release? = null) : UpdateState

    val canCheck: Boolean get() = this !is Checking && this !is Downloading && this !is SavingArchive
    val releaseToDownload: Release?
        get() = when (this) {
            is Available -> release
            is Failed -> release
            else -> null
        }
}
