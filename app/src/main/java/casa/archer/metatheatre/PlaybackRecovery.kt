package casa.archer.metatheatre

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource

internal enum class PlaybackFailure { CONNECTION, AUTHORIZATION, SERVER, MISSING, COMPATIBILITY, OTHER }

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun playbackFailure(error: PlaybackException): PlaybackFailure {
    val response = generateSequence(error.cause) { it.cause }.take(12)
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
    return when {
        response == 401 || response == 403 -> PlaybackFailure.AUTHORIZATION
        response == 404 || response == 410 -> PlaybackFailure.MISSING
        response == 408 || response == 429 || response != null && response >= 500 -> PlaybackFailure.SERVER
        error.errorCode in listOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) -> PlaybackFailure.CONNECTION
        error.errorCode in 3000..4999 -> PlaybackFailure.COMPATIBILITY
        else -> PlaybackFailure.OTHER
    }
}
