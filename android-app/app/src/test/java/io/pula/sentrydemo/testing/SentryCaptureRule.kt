package io.pula.sentrydemo.testing

import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.protocol.SentryTransaction
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * JUnit @Rule that initializes Sentry with a no-op DSN and a set of
 * `beforeSend*` interceptors that capture every transaction, event, and
 * breadcrumb the SUT emits. Interceptors return `null` for events and
 * transactions, so nothing is actually transported — tests run hermetically
 * with no network.
 *
 * Usage:
 * ```
 * @get:Rule val sentry = SentryCaptureRule()
 *
 * @Test fun something() {
 *     // ... exercise SUT ...
 *     assertThat(sentry.transactions).hasSize(1)
 *     assertThat(sentry.transactions[0].transaction).isEqualTo("photo_workflow")
 * }
 * ```
 */
class SentryCaptureRule : TestWatcher() {
    val transactions: MutableList<SentryTransaction> = mutableListOf()
    val events: MutableList<SentryEvent> = mutableListOf()
    val breadcrumbs: MutableList<Breadcrumb> = mutableListOf()

    override fun starting(description: Description) {
        transactions.clear()
        events.clear()
        breadcrumbs.clear()

        Sentry.init { options ->
            options.dsn = "http://publickey@localhost/1"
            options.tracesSampleRate = 1.0
            options.profilesSampleRate = 0.0
            options.isEnableAutoSessionTracking = false
            options.isDebug = false

            options.beforeSendTransaction =
                SentryOptions.BeforeSendTransactionCallback { tx, _ ->
                    transactions += tx
                    null
                }
            options.beforeSend =
                SentryOptions.BeforeSendCallback { event, _ ->
                    events += event
                    null
                }
            options.beforeBreadcrumb =
                SentryOptions.BeforeBreadcrumbCallback { crumb, _ ->
                    breadcrumbs += crumb
                    crumb
                }
        }
    }

    override fun finished(description: Description) {
        Sentry.close()
    }
}
