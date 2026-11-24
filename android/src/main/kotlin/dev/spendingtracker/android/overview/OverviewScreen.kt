package dev.spendingtracker.android.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.spendingtracker.android.data.SessionApi
import dev.spendingtracker.android.theme.GeistMono
import dev.spendingtracker.android.theme.LocalPalette
import dev.spendingtracker.android.theme.categoryHue
import dev.spendingtracker.android.ui.CategoryDot
import dev.spendingtracker.android.util.formatAmount
import dev.spendingtracker.android.util.formatMoney
import dev.spendingtracker.shared.api.Category
import dev.spendingtracker.shared.api.Overview
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** The month at a glance: the month's figures with pace, top categories and merchants. Read-only. */
@Composable
fun OverviewScreen(
    month: String,
    sessionApi: SessionApi,
    viewModel: OverviewViewModel = koinViewModel(parameters = { parametersOf(month) }),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val household by sessionApi.household.collectAsStateWithLifecycle()
    val currency = household?.currency ?: "CZK"

    PullToRefreshBox(
        isRefreshing = state is OverviewUiState.Loading,
        onRefresh = viewModel::load,
        modifier = Modifier.fillMaxSize(),
    ) {
        when (val s = state) {
            is OverviewUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is OverviewUiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(s.message, color = MaterialTheme.colorScheme.error)
            }
            is OverviewUiState.Ready -> OverviewContent(s.overview, s.categories, currency)
        }
    }
}

@Composable
private fun OverviewContent(overview: Overview, categories: List<Category>, currency: String) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        item { SummaryFigures(overview, currency) }
        if (overview.topCategories.isNotEmpty()) {
            item { SectionTitle("Top categories") }
            items(overview.topCategories.size) { index ->
                val total = overview.topCategories[index]
                val category = categories.find { it.id == total.categoryId }
                val parent = category?.parentId?.let { pid -> categories.find { it.id == pid } }
                val name = when {
                    category == null -> "Uncategorized"
                    parent != null -> "${parent.name} › ${category.name}"
                    else -> category.name
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CategoryDot(categories, total.categoryId)
                    Spacer(Modifier.weight(0.05f))
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        formatAmount(total.amountMinor, currency),
                        fontFamily = GeistMono,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        if (overview.topMerchants.isNotEmpty()) {
            item { SectionTitle("Top merchants") }
            items(overview.topMerchants.size) { index ->
                val total = overview.topMerchants[index]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(total.name.ifEmpty { "No merchant" }, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        formatAmount(total.amountMinor, currency),
                        fontFamily = GeistMono,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

/** Spending, income, saved and invested, the first one large like the web's `Summary`. */
@Composable
private fun SummaryFigures(overview: Overview, currency: String) {
    val flow = overview.thisMonth
    val saved = flow.incomeMinor - flow.spendingMinor
    val typical = overview.typicalMonth
    val pace = typical?.let { t ->
        if (t.spendingMinor > 0 && overview.daysInMonth > 0) {
            val expected = t.spendingMinor * overview.daysElapsed / overview.daysInMonth
            if (expected > 0) flow.spendingMinor.toDouble() / expected else null
        } else null
    }
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Figure("Spending", formatMoney(flow.spendingMinor, currency), pace?.let { "${(it * 100).toInt()}% of a typical month" })
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Figure("Income", formatMoney(flow.incomeMinor, currency))
            Figure("Saved", formatMoney(saved, currency))
            Figure("Invested", formatMoney(flow.investedMinor, currency))
        }
    }
}

@Composable
private fun Figure(label: String, value: String, sub: String? = null) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontFamily = GeistMono, style = MaterialTheme.typography.headlineMedium)
        if (sub != null) {
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
    HorizontalDivider()
}
