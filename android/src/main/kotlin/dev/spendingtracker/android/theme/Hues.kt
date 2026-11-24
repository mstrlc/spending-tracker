package dev.spendingtracker.android.theme

import androidx.compose.ui.graphics.Color
import dev.spendingtracker.shared.api.Category
import dev.spendingtracker.shared.api.CategoryKind
import dev.spendingtracker.shared.api.Member

/**
 * The same hue assignment as the web's chips.tsx: top-level categories take the eight hues in the list order
 * (income categories counted apart, as they're never listed with spending ones), members take them in their
 * list order, so a category or person looks the same in the app as on the web.
 */

/** A category's hue, from its top-level category; null for none (the web's "neutral" dashed dot). */
fun Palette.categoryHue(categories: List<Category>, id: String?): Color? {
    if (id == null) return null
    val category = categories.find { it.id == id } ?: return null
    val rootId = category.parentId ?: category.id
    val income = categories.find { it.id == rootId }?.kind == CategoryKind.INCOME
    val topLevel = categories.filter { it.parentId == null && (it.kind == CategoryKind.INCOME) == income }
    val index = topLevel.indexOfFirst { it.id == rootId }
    return if (index < 0) null else hues[index % hues.size]
}

/** A member's hue, in the members' list order; null for joint (the web's neutral group icon). */
fun Palette.memberHue(members: List<Member>, userId: String?): Color? {
    val index = members.indexOfFirst { it.userId == userId }
    return if (index < 0) null else hues[index % hues.size]
}
