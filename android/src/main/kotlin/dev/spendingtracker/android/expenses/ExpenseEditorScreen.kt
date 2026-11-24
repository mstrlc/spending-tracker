package dev.spendingtracker.android.expenses

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.spendingtracker.android.data.SessionApi
import dev.spendingtracker.android.theme.GeistMono
import dev.spendingtracker.android.util.formatDay
import dev.spendingtracker.shared.api.Account
import dev.spendingtracker.shared.api.Category
import dev.spendingtracker.shared.api.Member
import kotlinx.coroutines.flow.collectLatest
import dev.spendingtracker.android.ui.AppTopBar
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Create or edit one expense: date, amount, merchant, category, account, whose, notes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseEditorScreen(
    month: String,
    expenseId: String?,
    onDone: () -> Unit,
    sessionApi: SessionApi,
    viewModel: ExpenseEditorViewModel = koinViewModel(parameters = { parametersOf(month, expenseId) }),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is EditorEvent.Saved, is EditorEvent.Deleted -> onDone()
            }
        }
    }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            AppTopBar(
                title = if (expenseId == null) "New expense" else "Edit expense",
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when (val s = state) {
            is EditorUiState.Loading -> {}
            is EditorUiState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(padding))
            is EditorUiState.Ready -> {
                val editor = s.editor
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = formatDay(editor.date),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Date") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        trailingIcon = {
                            IconButton(onClick = { showDatePicker = true }) {
                                Text("Pick", style = MaterialTheme.typography.labelLarge)
                            }
                        },
                    )
                    OutlinedTextField(
                        value = editor.amountText,
                        onValueChange = viewModel::onAmountChange,
                        label = { Text("Amount") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = GeistMono),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = editor.merchant,
                        onValueChange = viewModel::onMerchantChange,
                        label = { Text("Merchant") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DropdownField(
                        label = "Category",
                        value = editor.categoryId,
                        options = listOf<Pair<String?, String>>(null to "Uncategorized") +
                            editor.categories.map { it.id to categoryName(editor.categories, it) },
                        onSelect = viewModel::onCategoryChange,
                    )
                    DropdownField(
                        label = "Account",
                        value = editor.accountId,
                        options = listOf<Pair<String?, String>>(null to "No account") +
                            editor.accounts.map { it.id to it.name },
                        onSelect = viewModel::onAccountChange,
                    )
                    DropdownField(
                        label = "Whose",
                        value = editor.paidByUserId,
                        options = listOf<Pair<String?, String>>(null to "Joint") +
                            editor.members.map { it.userId to it.displayName },
                        onSelect = viewModel::onWhoseChange,
                    )
                    OutlinedTextField(
                        value = editor.notes,
                        onValueChange = viewModel::onNotesChange,
                        label = { Text("Notes") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (editor.error != null) {
                        Text(editor.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        if (expenseId != null) {
                            TextButton(onClick = { showDeleteConfirm = true }) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        Button(onClick = viewModel::save, enabled = editor.canSave) {
                            Text(if (editor.saving) "Saving…" else "Save")
                        }
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val date = java.time.Instant.ofEpochMilli(millis)
                            .atZone(java.time.ZoneId.of("UTC"))
                            .toLocalDate()
                        viewModel.onDateChange(
                            kotlinx.datetime.LocalDate(date.year, date.monthValue, date.dayOfMonth)
                        )
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete expense?") },
            text = { Text("You can undo for a few seconds after it's deleted.") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; viewModel.delete() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
        )
    }
}

private fun categoryName(categories: List<Category>, category: Category): String {
    val parent = category.parentId?.let { pid -> categories.find { it.id == pid } }
    return if (parent != null) "${parent.name} › ${category.name}" else category.name
}

/** A read-only field that opens a Material 3 selection dialog (real list rows work better on phones than a popup). */
@Composable
private fun DropdownField(
    label: String,
    value: String?,
    options: List<Pair<String?, String>>,
    onSelect: (String?) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    val selectedLabel = options.find { it.first == value }?.second ?: options.first().second
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        // Invisible click target over the whole field.
        Box(Modifier.matchParentSize().clickable { showDialog = true })
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(label) },
            text = {
                LazyColumn {
                    items(options.size) { index ->
                        val (id, name) = options[index]
                        TextButton(
                            onClick = { onSelect(id); showDialog = false },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                name,
                                modifier = Modifier.fillMaxWidth(),
                                color = if (id == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Cancel") }
            },
        )
    }
}
