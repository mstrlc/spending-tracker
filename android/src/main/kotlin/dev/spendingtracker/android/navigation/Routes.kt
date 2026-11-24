package dev.spendingtracker.android.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

/** Navigation 3 keys; each is Parcelable so it can sit on the back stack. */
@Serializable
sealed interface Route : NavKey, android.os.Parcelable

@Parcelize
object Login : Route

@Parcelize
object Households : Route

/**
 * The main shell (Overview and Expenses tabs); [month] is YYYY-MM, paged with the arrows in the top bar.
 */
@Parcelize
data class Main(val month: String) : Route

/** Create ([expenseId] null) or edit one expense of [month]. */
@Parcelize
data class ExpenseEditor(val month: String, val expenseId: String?) : Route
