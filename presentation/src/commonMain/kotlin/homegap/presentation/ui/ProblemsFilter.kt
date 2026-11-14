package homegap.presentation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * The "All / Problems" switch of a list: the count of all items, the count of the items that have
 * a problem, which of the two shows, and the change. [label] names the second choice.
 */
@Immutable
data class ProblemsFilterModel(
    val total: Int,
    val problems: Int,
    val problemsOnly: Boolean,
    val onChange: (Boolean) -> Unit,
    val label: String = "Problems",
)

/** The filter of a list with [total] items of which [problems] have a problem. It starts with [initial]. */
@Composable
fun rememberProblemsFilter(
    total: Int,
    problems: Int,
    label: String = "Problems",
    initial: Boolean = false,
): ProblemsFilterModel {
    var problemsOnly by remember { mutableStateOf(initial) }
    return ProblemsFilterModel(
        total = total,
        problems = problems,
        problemsOnly = problemsOnly,
        onChange = { problemsOnly = it },
        label = label,
    )
}
