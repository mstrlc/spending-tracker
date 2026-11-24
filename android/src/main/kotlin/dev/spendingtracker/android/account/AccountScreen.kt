package dev.spendingtracker.android.account

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest
import dev.spendingtracker.android.data.SessionApi
import org.koin.compose.viewmodel.koinViewModel

/**
 * The account tab: who's logged in, the household and server in use (like the web sidebar's footer), and a
 * log out button. Logging out pops back to the login screen via [onLogout].
 */
@Composable
fun AccountScreen(
    sessionApi: SessionApi,
    onLogout: () -> Unit,
    viewModel: AccountViewModel = koinViewModel(),
) {
    val session by sessionApi.session.collectAsStateWithLifecycle()
    val household by sessionApi.household.collectAsStateWithLifecycle()
    val serverUrl by sessionApi.serverUrl.collectAsStateWithLifecycle()
    var showLogoutConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            if (event is AccountEvent.LoggedOut) onLogout()
        }
    }

    Scaffold { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            item {
                ListItem(
                    leadingContent = {
                        val name = session?.me?.displayName.orEmpty()
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                name.firstOrNull()?.uppercase() ?: "?",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    headlineContent = { Text(session?.me?.displayName ?: "") },
                    supportingContent = { Text(session?.me?.email ?: "") },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(household?.name ?: "") },
                    supportingContent = { Text(household?.currency ?: "") },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Server") },
                    supportingContent = { Text(serverUrl) },
                )
            }
            item {
                ListItem(
                    headlineContent = {
                        Text("Log out", color = MaterialTheme.colorScheme.error)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showLogoutConfirm = true },
                )
            }
        }
        if (showLogoutConfirm) {
            AlertDialog(
                onDismissRequest = { showLogoutConfirm = false },
                title = { Text("Log out?") },
                text = { Text("You'll need to log in again on this device.") },
                confirmButton = {
                    TextButton(onClick = { showLogoutConfirm = false; viewModel.logout() }) {
                        Text("Log out", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { TextButton(onClick = { showLogoutConfirm = false }) { Text("Cancel") } },
            )
        }
    }
}
