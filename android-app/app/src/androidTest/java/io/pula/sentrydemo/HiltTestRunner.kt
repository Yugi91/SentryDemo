package io.pula.sentrydemo

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Custom test runner that swaps in `HiltTestApplication` instead of the
 * production `SentryDemoApplication`. Wired in via `app/build.gradle.kts`:
 * `testInstrumentationRunner = "io.pula.sentrydemo.HiltTestRunner"`.
 */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
