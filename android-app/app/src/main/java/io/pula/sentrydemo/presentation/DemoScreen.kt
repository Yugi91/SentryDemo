package io.pula.sentrydemo.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.pula.sentrydemo.BuildConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoScreen(viewModel: MainViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("SentryDemo · v${BuildConfig.VERSION_NAME}") })
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("DSN: ${BuildConfig.SENTRY_DSN.ifBlank { "<not set in local.properties>" }}", style = MaterialTheme.typography.bodySmall)
            Text("Env: ${BuildConfig.SENTRY_ENV}  ·  user: ${BuildConfig.DEMO_USER_ID}", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()

            DemoButton(
                title = "1. Delay action (2 s)",
                subtitle = "Transaction `delay_action` with a child span. Performance tab in Sentry.",
                enabled = !state.busy,
            ) { viewModel.onSimulateDelay() }

            DemoButton(
                title = "2. Crash action",
                subtitle = "Uncaught RuntimeException — caught by Sentry on process death.",
                enabled = true,
            ) { viewModel.onTriggerCrash() }

            DemoButton(
                title = "3. ANR action (block 8 s)",
                subtitle = "Busy-loops main thread for 8 s. Trips Sentry's 5 s watchdog.",
                enabled = true,
            ) { viewModel.onTriggerAnr() }

            DemoButton(
                title = "4. Photo workflow (ok path)",
                subtitle = "Transaction `photo_workflow` with capture / save / sync child spans.",
                enabled = !state.busy,
            ) { viewModel.onRunWorkflow(forceFailure = false) }

            DemoButton(
                title = "4b. Photo workflow (force fail)",
                subtitle = "Same workflow, but sync_image throws — span marked failed, exception captured.",
                enabled = !state.busy,
            ) { viewModel.onRunWorkflow(forceFailure = true) }

            Spacer(modifier = Modifier.height(8.dp))

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                Text("Activity log", style = MaterialTheme.typography.titleSmall)
                if (state.busy) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(20.dp))
                    }
                }
            }

            LogList(state.statusLog)
        }
    }
}

@Composable
private fun DemoButton(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = enabled, onClick = onClick) {
                Text("Run")
            }
        }
    }
}

@Composable
private fun LogList(lines: List<String>) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            contentPadding = PaddingValues(8.dp),
        ) {
            items(lines) { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
