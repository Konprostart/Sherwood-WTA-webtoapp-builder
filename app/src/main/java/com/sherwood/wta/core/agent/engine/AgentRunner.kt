package com.sherwood.wta.core.agent.engine

import com.sherwood.wta.core.agent.engine.AgentEngine.Input
import kotlinx.coroutines.flow.Flow

/**
 * Something that turns one dispatch into a stream of [AgentEvent]s.
 *
 * The built-in agent loop is [AgentEngine], which is the only implementation shipped
 * today. The interface exists so an alternative runtime — a local instruction executor,
 * an Open-Code-style driver, a scripted runner used in tests — can be selected without
 * touching `AgentService`, the session store or the UI: those only ever consume the
 * event stream.
 */
fun interface AgentRunner {
    fun run(input: Input): Flow<AgentEvent>
}
