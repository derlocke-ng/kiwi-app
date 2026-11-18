package io.github.derlocke_ng.kiwi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.derlocke_ng.kiwi.R

/** Home: a search field at the top, rows of cards below once there is data. */
@Composable
fun HomeScreen() {
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        TextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.search)) },
            leadingIcon = {
                Icon(painterResource(R.drawable.ic_search), contentDescription = null)
            },
            singleLine = true,
            shape = CircleShape,
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
        NothingYet(Modifier.weight(1f))
    }
}

@Composable
fun EmptyScreen(destination: Destination) {
    Column(Modifier.fillMaxSize()) {
        Text(
            text = stringResource(destination.label),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
            style = MaterialTheme.typography.headlineLarge,
        )
        NothingYet(Modifier.weight(1f))
    }
}

@Composable
private fun NothingYet(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.nothing_yet),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
