package com.sherwood.wta.core.agent.permission

enum class PermissionMode { Default, AutoApprove, Plan, Dream }

enum class PermissionDecision { Allow, Deny }

data class PermissionRequest(
    val toolCallId: String,
    val toolName: String,
    val activity: String?,

    val argsPreview: Map<String, String>,

    /**
     * False for a tool that must be approved on every call (see
     * `Tool.requiresFreshApproval`): the dialog hides "Always allow" and the decision is
     * never remembered, so a push to a remote is never pre-authorized.
     */
    val allowAlways: Boolean = true
)

sealed class PermissionResponse {
    object Allow : PermissionResponse()
    object Deny : PermissionResponse()

    object AlwaysAllow : PermissionResponse()
}
