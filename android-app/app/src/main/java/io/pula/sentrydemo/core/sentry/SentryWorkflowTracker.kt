package io.pula.sentrydemo.core.sentry

import io.sentry.Breadcrumb
import io.sentry.ISpan
import io.sentry.ITransaction
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.SpanStatus
import io.sentry.TransactionOptions
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tiny DSL on top of Sentry transactions for tracking multi-step workflows.
 *
 * Each step becomes a Sentry span; data goes onto the span via [StepScope.data];
 * thrown exceptions mark the span as failed (and rethrow). A breadcrumb is
 * emitted for every step transition so the chain is visible even on crashed
 * workflows where the transaction itself never finishes.
 */
@Singleton
class SentryWorkflowTracker @Inject constructor() {

    suspend fun <T> runRoot(
        name: String,
        operation: String,
        block: suspend WorkflowScope.() -> T,
    ): T {
        val tx = Sentry.startTransaction(name, operation, TransactionOptions().apply {
            isBindToScope = true
        })
        crumb(name, "workflow.started", SentryLevel.INFO)
        val scope = WorkflowScopeImpl(workflowName = name, transaction = tx)
        return try {
            val result = scope.block()
            tx.finish(SpanStatus.OK)
            crumb(name, "workflow.finished", SentryLevel.INFO)
            result
        } catch (t: Throwable) {
            tx.throwable = t
            tx.finish(SpanStatus.INTERNAL_ERROR)
            crumb(name, "workflow.failed: ${t.message}", SentryLevel.ERROR)
            Sentry.captureException(t)
            throw t
        }
    }

    interface WorkflowScope {
        suspend fun <T> step(name: String, block: suspend StepScope.() -> T): T
    }

    interface StepScope {
        fun data(key: String, value: Any?)
        fun markFailed(reason: String)
    }

    private class WorkflowScopeImpl(
        private val workflowName: String,
        private val transaction: ITransaction,
    ) : WorkflowScope {
        override suspend fun <T> step(name: String, block: suspend StepScope.() -> T): T {
            val span: ISpan = transaction.startChild(name)
            val scope = StepScopeImpl(span)
            crumb(workflowName, "$name.started", SentryLevel.INFO)
            return try {
                val result = scope.block()
                span.finish(if (scope.failed) SpanStatus.INTERNAL_ERROR else SpanStatus.OK)
                crumb(
                    workflowName,
                    "$name.finished status=${if (scope.failed) "FAILED" else "OK"}",
                    if (scope.failed) SentryLevel.WARNING else SentryLevel.INFO,
                )
                result
            } catch (t: Throwable) {
                span.throwable = t
                span.finish(SpanStatus.INTERNAL_ERROR)
                crumb(workflowName, "$name.failed: ${t.message}", SentryLevel.ERROR)
                throw t
            }
        }
    }

    private class StepScopeImpl(private val span: ISpan) : StepScope {
        var failed: Boolean = false
            private set

        override fun data(key: String, value: Any?) {
            span.setData(key, value ?: "null")
        }

        override fun markFailed(reason: String) {
            failed = true
            span.setData("error_reason", reason)
        }
    }

    companion object {
        private fun crumb(category: String, message: String, level: SentryLevel) {
            Sentry.addBreadcrumb(Breadcrumb().apply {
                type = "workflow"
                this.category = category
                this.message = message
                this.level = level
            })
        }
    }
}
