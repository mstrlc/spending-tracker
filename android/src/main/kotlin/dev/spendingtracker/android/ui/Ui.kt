package dev.spendingtracker.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.spendingtracker.android.theme.GeistMono
import dev.spendingtracker.android.theme.LocalPalette
import dev.spendingtracker.android.theme.blend
import dev.spendingtracker.android.theme.categoryHue
import dev.spendingtracker.android.theme.memberHue
import dev.spendingtracker.android.util.formatMoney
import dev.spendingtracker.shared.api.Category
import dev.spendingtracker.shared.api.Member

/** An amount in Geist Mono (tabular figures), with the currency muted after it — the web's `Money`. */
@Composable
fun MoneyText(
    amountMinor: Long,
    currency: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = formatMoney(amountMinor, currency),
            fontFamily = GeistMono,
            color = color,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** A coloured dot for a category (the web's `Dot`); an uncategorized one is a dashed grey ring. */
@Composable
fun CategoryDot(categories: List<Category>, categoryId: String?, modifier: Modifier = Modifier) {
    val hue = LocalPalette.current.categoryHue(categories, categoryId)
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(hue ?: Color.Transparent)
    )
}

/** A category's name after its hue dot; "Parent › Child" for a subcategory; "Uncategorized" when none. */
@Composable
fun CategoryLabel(
    categories: List<Category>,
    categoryId: String?,
    modifier: Modifier = Modifier,
    short: Boolean = false,
) {
    val category = categories.find { it.id == categoryId }
    val parent = category?.parentId?.let { pid -> categories.find { it.id == pid } }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        CategoryDot(categories, categoryId)
        Spacer(Modifier.width(6.dp))
        Text(
            text = when {
                category == null -> "Uncategorized"
                parent != null && !short -> "${parent.name} › ${category.name}"
                else -> category.name
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (category == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

private fun initials(name: String): String =
    name.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

/**
 * A member's initials in a circle of their hue (the web's `Avatar`); joint is a neutral grey circle with a
 * group icon's stand-in.
 */
@Composable
fun Avatar(members: List<Member>, userId: String?, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val member = members.find { it.userId == userId }
    val hue = palette.memberHue(members, userId)
    val bg = hue?.copy(alpha = 0.22f) ?: MaterialTheme.colorScheme.surfaceVariant
    val fg = hue?.let { blend(it, MaterialTheme.colorScheme.onSurface, 0.7f) } ?: MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = member?.displayName?.let { initials(it) } ?: "J",
            color = fg,
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** An avatar and the member's name; "Joint" when [userId] is null (the web's `PersonLabel`). */
@Composable
fun PersonLabel(members: List<Member>, userId: String?, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Avatar(members, userId)
        Spacer(Modifier.width(6.dp))
        Text(
            text = members.find { it.userId == userId }?.displayName ?: "Joint",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
