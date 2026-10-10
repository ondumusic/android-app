package com.ondumusic.player.api

import com.ondumusic.player.R
import com.ondumusic.player.util.UiText
import kotlinx.serialization.SerializationException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * The platform's own descriptions for network and decoding failures read as diagnostics
 * ("Unable to resolve host", "ECONNREFUSED"). These say which part of the setup to look at.
 */
object ErrorMessages {
    fun login(error: Throwable): UiText = connection(error) ?: when (error) {
        is LoginRejection.InvalidCredentials -> UiText.Res(R.string.error_wrong_credentials)
        is LoginRejection.TooManyAttempts -> UiText.Res(R.string.error_too_many_attempts)
        is SerializationException, is ApiException.Decoding, is ApiException.InvalidResponse ->
            UiText.Res(R.string.error_not_an_ondu_server)
        is ApiException.Http if (error.code == 404 || error.code == 405) -> UiText.Res(R.string.error_not_an_ondu_server)
        else -> fallback(error)
    }

    fun sync(error: Throwable): UiText = connection(error) ?: when (error) {
        is SerializationException, is ApiException.Decoding, is ApiException.InvalidResponse ->
            UiText.Res(R.string.error_unreadable_response)
        is ApiException.Http if error.code == 404 -> UiText.Res(R.string.error_unreadable_response)
        else -> fallback(error)
    }

    private fun connection(error: Throwable): UiText? = when (error) {
        is UnknownHostException -> UiText.Res(R.string.error_cannot_find_host)
        is ConnectException, is SocketTimeoutException, is NoRouteToHostException ->
            UiText.Res(R.string.error_cannot_reach_server)
        is SSLException -> UiText.Res(R.string.error_insecure_connection)
        is ApiException.Http if error.code >= 500 -> UiText.Res(R.string.error_server_problem)
        else -> null
    }

    private fun fallback(error: Throwable): UiText =
        error.message?.let(UiText::Raw) ?: UiText.Res(R.string.error_generic)
}
