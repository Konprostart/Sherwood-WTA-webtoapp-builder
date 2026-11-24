package com.sherwood.wta.core.agent.autoprompt

/**
 * Hands a prepared Auto Prompt run to the agent screen.
 *
 * The run itself is prepared outside the agent screen (a new session, the user message and
 * the model/profile choice are already persisted), but the turn can only be dispatched by
 * the screen's view model — it owns the bound agent service and the dispatch pipeline.
 * This one-shot slot is the seam between the two.
 *
 * Deliberate properties:
 *
 *  - **One shot.** A request is consumed exactly once. Opening the session again later
 *    must never re-run an old prompt.
 *  - **Session-scoped.** A request only fires for the session it was prepared for, so
 *    opening some other session cannot run it.
 *  - **Expiring.** A request that was never consumed (the user navigated somewhere else)
 *    is dropped after [TTL_MS] instead of surprising them minutes later.
 *  - **In-memory only.** Nothing about a pending run is persisted; a restart leaves no
 *    half-run prompt behind.
 */
object AutoPromptHandoff {

    data class Request(
        val sessionId: String,
        val promptText: String,
        val profileId: String?,
        val modelId: String?,
        val createdAt: Long = System.currentTimeMillis()
    )

    /** A run prepared but never opened expires instead of firing much later. */
    const val TTL_MS: Long = 5 * 60 * 1000L

    @Volatile
    private var pending: Request? = null

    @Synchronized
    fun publish(request: Request) {
        pending = request
    }

    /**
     * Returns the request for [sessionId] and clears it.
     *
     * A request for a different session stays pending: the agent screen may still be
     * attaching another session first. An expired request is cleared and ignored.
     */
    @Synchronized
    fun consume(sessionId: String, now: Long = System.currentTimeMillis()): Request? {
        val request = pending ?: return null
        if (request.sessionId != sessionId) return null
        pending = null
        if (now - request.createdAt > TTL_MS) return null
        return request
    }

    /** Non-destructive check used by the agent screen before flipping its UI state. */
    @Synchronized
    fun peek(sessionId: String, now: Long = System.currentTimeMillis()): Request? {
        val request = pending ?: return null
        if (request.sessionId != sessionId) return null
        if (now - request.createdAt > TTL_MS) {
            pending = null
            return null
        }
        return request
    }

    @Synchronized
    fun clear() {
        pending = null
    }
}
