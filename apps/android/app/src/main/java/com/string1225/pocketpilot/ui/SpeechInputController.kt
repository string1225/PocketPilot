package com.string1225.pocketpilot.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.string1225.pocketpilot.model.AppLanguage
import java.io.Closeable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SpeechInputPhase {
    IDLE,
    STARTING,
    LISTENING,
    PROCESSING,
    ERROR,
}

enum class SpeechInputFailure {
    PERMISSION_DENIED,
    UNAVAILABLE,
    NO_MATCH,
    NETWORK,
    BUSY,
    UNKNOWN,
}

data class SpeechInputState(
    val phase: SpeechInputPhase = SpeechInputPhase.IDLE,
    val failure: SpeechInputFailure? = null,
) {
    val isActive: Boolean
        get() = phase == SpeechInputPhase.STARTING ||
            phase == SpeechInputPhase.LISTENING ||
            phase == SpeechInputPhase.PROCESSING
}

internal sealed interface SpeechInputEvent {
    data object StartRequested : SpeechInputEvent
    data object Ready : SpeechInputEvent
    data object EndOfSpeech : SpeechInputEvent
    data object Finished : SpeechInputEvent
    data class Failed(val failure: SpeechInputFailure) : SpeechInputEvent
    data object Cancelled : SpeechInputEvent
}

internal fun reduceSpeechInputState(
    current: SpeechInputState,
    event: SpeechInputEvent,
): SpeechInputState = when (event) {
    SpeechInputEvent.StartRequested -> SpeechInputState(SpeechInputPhase.STARTING)
    SpeechInputEvent.Ready -> if (current.isActive) {
        SpeechInputState(SpeechInputPhase.LISTENING)
    } else {
        current
    }
    SpeechInputEvent.EndOfSpeech -> if (current.isActive) {
        SpeechInputState(SpeechInputPhase.PROCESSING)
    } else {
        current
    }
    SpeechInputEvent.Finished,
    SpeechInputEvent.Cancelled,
    -> SpeechInputState()
    is SpeechInputEvent.Failed -> SpeechInputState(SpeechInputPhase.ERROR, event.failure)
}

/**
 * Small platform seam around [SpeechRecognizer]. Compose only observes state
 * and invokes this interface, which keeps Android callbacks out of UI state.
 */
interface SpeechInputController : Closeable {
    val state: StateFlow<SpeechInputState>

    /** Returns false when recognition could not be started. */
    fun start(
        language: AppLanguage,
        initialText: String,
        onText: (String) -> Unit,
        onFailure: (SpeechInputFailure) -> Unit,
    ): Boolean

    fun stop()
}

class AndroidSpeechInputController(
    context: Context,
) : SpeechInputController {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(SpeechInputState())
    override val state: StateFlow<SpeechInputState> = mutableState.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var sessionGeneration = 0L
    private var sessionPrefix = ""
    private var sessionActive = false
    private var closed = false
    private var textCallback: ((String) -> Unit)? = null
    private var failureCallback: ((SpeechInputFailure) -> Unit)? = null

    private fun createListener(generation: Long) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = dispatchIfActive(generation, SpeechInputEvent.Ready)

        override fun onBeginningOfSpeech() = dispatchIfActive(generation, SpeechInputEvent.Ready)

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = dispatchIfActive(generation, SpeechInputEvent.EndOfSpeech)

        override fun onError(error: Int) {
            if (!isCurrentSession(generation)) return
            val failure = error.toSpeechInputFailure()
            sessionActive = false
            dispatch(SpeechInputEvent.Failed(failure))
            failureCallback?.invoke(failure)
            clearCallbacks()
        }

        override fun onResults(results: Bundle?) {
            if (!isCurrentSession(generation)) return
            emitBestResult(results)
            sessionActive = false
            dispatch(SpeechInputEvent.Finished)
            clearCallbacks()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (isCurrentSession(generation)) emitBestResult(partialResults)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    override fun start(
        language: AppLanguage,
        initialText: String,
        onText: (String) -> Unit,
        onFailure: (SpeechInputFailure) -> Unit,
    ): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Speech recognition must be started on the main thread"
        }
        if (closed) return failStart(SpeechInputFailure.UNAVAILABLE, onFailure)
        if (
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return failStart(SpeechInputFailure.PERMISSION_DENIED, onFailure)
        }
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            return failStart(SpeechInputFailure.UNAVAILABLE, onFailure)
        }

        stopInternal(dispatchCancelled = false, disposeRecognizer = true)
        val generation = ++sessionGeneration
        val activeRecognizer = runCatching {
            recognizer ?: SpeechRecognizer.createSpeechRecognizer(appContext).also {
                it.setRecognitionListener(createListener(generation))
                recognizer = it
            }
        }.getOrElse {
            return failStart(SpeechInputFailure.UNAVAILABLE, onFailure)
        }

        sessionPrefix = initialText.trimEnd()
        textCallback = onText
        failureCallback = onFailure
        sessionActive = true
        dispatch(SpeechInputEvent.StartRequested)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                if (language == AppLanguage.ENGLISH) "en-US" else "zh-CN",
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        return runCatching {
            activeRecognizer.startListening(intent)
            true
        }.getOrElse {
            sessionActive = false
            dispatch(SpeechInputEvent.Failed(SpeechInputFailure.UNAVAILABLE))
            onFailure(SpeechInputFailure.UNAVAILABLE)
            clearCallbacks()
            false
        }
    }

    override fun stop() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Speech recognition must be stopped on the main thread"
        }
        stopInternal(dispatchCancelled = true, disposeRecognizer = true)
    }

    override fun close() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            closeOnMain()
        } else {
            mainHandler.post(::closeOnMain)
        }
    }

    private fun closeOnMain() {
        if (closed) return
        closed = true
        stopInternal(dispatchCancelled = true, disposeRecognizer = true)
    }

    private fun stopInternal(
        dispatchCancelled: Boolean,
        disposeRecognizer: Boolean,
    ) {
        sessionGeneration += 1
        if (sessionActive) runCatching { recognizer?.cancel() }
        sessionActive = false
        clearCallbacks()
        if (disposeRecognizer) {
            recognizer?.destroy()
            recognizer = null
        }
        if (dispatchCancelled) dispatch(SpeechInputEvent.Cancelled)
    }

    private fun emitBestResult(bundle: Bundle?) {
        val recognized = bundle
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (recognized.isEmpty()) return
        val combined = if (sessionPrefix.isEmpty()) recognized else "$sessionPrefix $recognized"
        textCallback?.invoke(combined)
    }

    private fun dispatchIfActive(generation: Long, event: SpeechInputEvent) {
        if (isCurrentSession(generation)) dispatch(event)
    }

    private fun isCurrentSession(generation: Long): Boolean =
        sessionActive && generation == sessionGeneration

    private fun dispatch(event: SpeechInputEvent) {
        mutableState.value = reduceSpeechInputState(mutableState.value, event)
    }

    private fun failStart(
        failure: SpeechInputFailure,
        callback: (SpeechInputFailure) -> Unit,
    ): Boolean {
        dispatch(SpeechInputEvent.Failed(failure))
        callback(failure)
        return false
    }

    private fun clearCallbacks() {
        textCallback = null
        failureCallback = null
    }
}

object SpeechInputControllerFactory {
    fun create(context: Context): SpeechInputController = AndroidSpeechInputController(context)
}

private fun Int.toSpeechInputFailure(): SpeechInputFailure = when (this) {
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechInputFailure.PERMISSION_DENIED
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SpeechInputFailure.BUSY
    SpeechRecognizer.ERROR_NETWORK,
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
    SpeechRecognizer.ERROR_SERVER,
    -> SpeechInputFailure.NETWORK
    SpeechRecognizer.ERROR_NO_MATCH,
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
    -> SpeechInputFailure.NO_MATCH
    else -> SpeechInputFailure.UNKNOWN
}
