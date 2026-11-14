package com.sherwood.wta.core.agent.tool

import com.google.gson.JsonElement
import com.google.gson.JsonObject

interface Tool {

    val name: String

    val description: String

    val parametersSchema: JsonElement

    fun isReadOnly(): Boolean = false

    /**
     * Tools whose side effect is irreversible on a remote system.
     *
     * The permission gate treats them as one-shot: "Always allow" is neither offered
     * in the prompt nor honoured for them, so every invocation needs a fresh tap.
     * Default false — every other tool keeps the existing behaviour.
     */
    fun requiresFreshApproval(): Boolean = false

    fun activityDescription(args: JsonObject): String? = null

    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}
