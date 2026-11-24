package dev.spendingtracker.android.households

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest
import dev.spendingtracker.android.ui.AppTopBar
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun HouseholdsScreen(
    onOpen: (String) -> Unit,
    viewModel: HouseholdsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            if (event is HouseholdsEvent.Open) onOpen(event.household.id)
        }
    }

    Scaffold(
        topBar = { AppTopBar(title = "Your households") },
    ) { padding ->
        when (val s = state) {
            is HouseholdsUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is HouseholdsUiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(s.message, color = MaterialTheme.colorScheme.error)
            }
            is HouseholdsUiState.Ready -> LazyColumn(modifier = Modifier.padding(padding)) {
                items(s.households, key = { it.id }) { household ->
                    ListItem(
                        headlineContent = { Text(household.name) },
                        supportingContent = { Text(household.currency) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.pick(household) },
                    )
                }
            }
        }
    }
}
