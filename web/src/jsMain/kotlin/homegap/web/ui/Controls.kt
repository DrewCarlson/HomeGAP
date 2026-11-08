package homegap.web.ui

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.*

enum class ButtonStyle(val className: String?) { Default(null), Primary("primary"), Danger("danger") }

@Composable
fun ActionButton(
    text: String,
    style: ButtonStyle = ButtonStyle.Default,
    small: Boolean = false,
    enabled: Boolean = true,
    tooltip: String? = null,
    onClick: () -> Unit,
) {
    Button({
        classes("btn")
        style.className?.let { classes(it) }
        if (small) classes("sm")
        if (!enabled) disabled()
        tooltip?.let { title(it) }
        onClick { onClick() }
    }) { Text(text) }
}

/** Labelled single-line text input. */
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

/** Labelled multi-line text input. */
@Composable
fun TextAreaField(label: String, value: String, placeholder: String, rows: Int = 4, mono: Boolean = false, onChange: (String) -> Unit) {
    Label {
        Text(label)
        TextArea(value) {
            placeholder(placeholder)
            attr("rows", rows.toString())
            attr("spellcheck", "false")
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
