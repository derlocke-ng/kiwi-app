package io.github.derlocke_ng.kiwi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.derlocke_ng.kiwi.R
import io.github.derlocke_ng.kiwi.Shown
import io.github.derlocke_ng.kiwi.core.Activity
import io.github.derlocke_ng.kiwi.core.Entry
import io.github.derlocke_ng.kiwi.core.Job

/** Activity: the tasks the control node ran, and who did what. Read when the screen opens, and on a pull. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(activity: Shown<Activity>, onRead: () -> Unit) {
    LaunchedEffect(Unit) { onRead() }
    PullToRefreshBox(isRefreshing = activity.loading, onRefresh = onRead, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Title(stringResource(R.string.activity)) }
            item { Trouble(activity.problem, activity.detail) }
            val found = activity.value
            if (found != null) {
                if (found.jobs.isEmpty() && found.entries.isEmpty()) item { NothingYet() }
                if (found.jobs.isNotEmpty()) item { Part(R.string.activity_tasks) }
                items(found.jobs, key = { "job-" + it.id }) { JobCard(it) }
                if (found.entries.isNotEmpty()) item { Part(R.string.activity_who) }
                items(found.entries) { EntryCard(it) }
            }
        }
    }
}

@Composable
private fun Part(title: Int) {
    Text(stringResource(title), Modifier.padding(top = 16.dp, bottom = 4.dp), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun JobCard(job: Job) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text((listOf(job.action) + job.machines).joinToString(" "), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = listOf(job.user, whenOf(job.id)).filter { it.isNotEmpty() }.joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(job.state, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun EntryCard(entry: Entry) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text((listOf(entry.what) + entry.machines).joinToString(" "))
            Text(
                text = listOf(entry.user, entry.time.replace('T', ' ').take(16)).filter { it.isNotEmpty() }.joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** A task's name begins with its day and time: 20261118-054012-… */
private fun whenOf(id: String): String =
    Regex("""(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})\d{2}-.*""").matchEntire(id)?.destructured
        ?.let { (y, m, d, h, min) -> "$y-$m-$d $h:$min" }.orEmpty()
