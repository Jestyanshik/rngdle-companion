package app.rngdle.companion

import app.rngdle.companion.update.Release
import java.io.File

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object Current : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data class Ready(val apk: File) : UpdateState
    data class Failed(val release: Release? = null) : UpdateState

    val canCheck: Boolean get() = this !is Checking && this !is Downloading
    val releaseToDownload: Release?
        get() = when (this) {
            is Available -> release
            is Failed -> release
            else -> null
        }
}
