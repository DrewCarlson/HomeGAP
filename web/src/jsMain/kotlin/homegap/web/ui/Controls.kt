package homegap.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.attributes.rows
import org.jetbrains.compose.web.dom.*

enum class ButtonStyle(val className: String?) { Default(null), Primary("primary"), Danger("danger") }

/** True in a read-only view (a share link): buttons that change something are turned off. */
val LocalReadOnly = staticCompositionLocalOf { false }

/** Why a button is off in a read-only view. */
const val READ_ONLY_TOOLTIP = "Read-only view: nothing can be changed here"

/** A button; one that [mutates] is turned off in a read-only view. */
@Composable
fun ActionButton(
    text: String,
    style: ButtonStyle = ButtonStyle.Default,
    small: Boolean = false,
    enabled: Boolean = true,
    tooltip: String? = null,
    mutates: Boolean = true,
    onClick: () -> Unit,
) {
    val locked = mutates && LocalReadOnly.current
    Button({
        classes("btn")
        style.className?.let { classes(it) }
        if (small) classes("sm")
        if (!enabled || locked) disabled()
        (if (locked) READ_ONLY_TOOLTIP else tooltip)?.let { title(it) }
        onClick { onClick() }
    }) { Text(text) }
}

/** Labeled single-line text input. */
@Composable
fun TextField(
    label: String,
    value: String,
    placeholder: String,
    type: InputType<String> = InputType.Text,
    onChange: (String) -> Unit,
) {
    Label {
        Text(label)
        Input(type) {
            value(value)
            placeholder(placeholder)
            onInput { onChange(it.value) }
        }
    }
}

/** Pill buttons with one active option. */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, enabled: Boolean = true, onSelect: (T) -> Unit) {
    Div({ classes("segmented") }) {
        options.forEach { option ->
            Button({
                if (option == selected) classes("active")
                if (!enabled) disabled()
                onClick { onSelect(option) }
            }) { Text(label(option)) }
        }
    }
}

/** In-app link: keeps a real href (new tab, copy link) but navigates through the presenter. */
@Composable
fun NavLink(href: String, onNavigate: () -> Unit, classNames: List<String> = emptyList(), content: @Composable () -> Unit) {
    A(href = href, {
        if (classNames.isNotEmpty()) classes(classNames)
        onClick { event ->
            if (!event.ctrlKey && !event.metaKey && !event.shiftKey) {
                event.preventDefault()
                onNavigate()
            }
        }
    }) { content() }
}

/** Labeled multi-line text input. */
@Composable
fun TextAreaField(label: String, value: String, placeholder: String, rows: Int = 4, mono: Boolean = false, onChange: (String) -> Unit) {
    Label {
        Text(label)
        TextArea(value) {
            placeholder(placeholder)
            rows(rows)
            spellCheck(false)
            if (mono) classes("mono")
            onInput { onChange(it.value) }
        }
    }
}

/** A checkbox with a title and an explanation underneath. */
@Composable
fun CheckOption(title: String, description: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Label(attrs = {
        classes("option")
        if (!enabled) classes("disabled")
    }) {
        CheckboxInput(checked) {
            if (!enabled) disabled()
            onChange { onChange(it.value) }
        }
        Span({ classes("option-text") }) {
            Span({ classes("option-title") }) { Text(title) }
            description?.let { Span({ classes("option-desc") }) { Text(it) } }
        }
    }
}

/** A square button showing only an icon; [label] names it for screen readers and the tooltip. */
@Composable
fun IconButton(
    iconPath: String,
    label: String,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    Button({
        classes("btn", "icon-btn")
        if (highlighted) {
            classes("highlighted")
        }
        if (!enabled) {
            disabled()
        }
        attr("aria-label", label)
        title(label)
        onClick { onClick() }
    }) {
        Icon(iconPath)
    }
}
