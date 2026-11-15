package com.sherwood.wta.core.studio

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * One finished branch build, as observed by the composer.
 *
 * This carries no credential and no file content: the artifact list is already in the
 * vault, and a listener only needs enough to say "build finished".
 */
data class BuildFinishedEvent(
    val owner: String,
    val repo: String,
    val branch: String,
    val runId: Long,
    val conclusion: String?,
    val succeeded: Boolean,
    val artifactNames: List<String>,
    val downloadError: String?,
    /** Web page of the run, when the host reports one. */
    val runUrl: String? = null
) {
    val repository: String get() = "$owner/$repo"
}

/**
 * In-process fan-out of finished builds.
 *
 * `buildBranch` is called from the Builds tab and from the Telegram harness; both need
 * the same "a build just finished" signal, and neither should have to poll. Emission is
 * best-effort (`tryEmit`) on purpose: a build must never fail because a notification
 * listener is slow or absent.
 */
object BuildEvents {

    private val _events = MutableSharedFlow<BuildFinishedEvent>(extraBufferCapacity = 8)

    val events: SharedFlow<BuildFinishedEvent> = _events

    fun publish(event: BuildFinishedEvent) {
        _events.tryEmit(event)
    }
}
