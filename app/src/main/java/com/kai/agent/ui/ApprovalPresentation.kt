package com.kai.agent.ui

import com.kai.agent.data.AgentTask
import com.kai.agent.data.TaskStatus

/** Trusted reads use the operation ledger internally; they do not require a user review dialog. */
object ApprovalPresentation {
    fun requiresReview(task: AgentTask)=task.status==TaskStatus.AWAITING_APPROVAL && task.intent?.readOnly!=true
}
