package homegap.web.ui

import androidx.compose.runtime.Composable
import homegap.presentation.ui.FieldKind
import homegap.presentation.ui.ProblemsFilterModel
import org.jetbrains.compose.web.attributes.AttrsScope
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.attributes.rows
import org.jetbrains.compose.web.dom.*
import org.w3c.dom.HTMLTableRowElement

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

/** Labeled single-line text input. */
@Composable
fun TextField(
    label: String,
    value: String,
    placeholder: String,
    type: InputType<String> = InputType.Text,
    kind: FieldKind = FieldKind.Text,
    error: String? = null,
    onChange: (String) -> Unit,
) {
    Label {
        Text(label)
        Input(type) {
            value(value)
            placeholder(placeholder)
            if (kind != FieldKind.Text) {
                attr("autocapitalize", "off")
                attr("autocorrect", "off")
                spellCheck(false)
            }
            when (kind) {
                FieldKind.Number -> attr("inputmode", "numeric")
                FieldKind.Host -> attr("inputmode", "url")
                else -> Unit
            }
            if (error != null) {
                classes("invalid")
                attr("aria-invalid", "true")
            }
            onInput { onChange(it.value) }
        }
        // Inside the label, so a screen reader reads the problem with the field.
        error?.let { Span({ classes("field-note", "error") }) { Text(it) } }
    }
}

/**
 * Makes a table row a stop for the Tab key. Enter on the row runs [onOpen], as a click does. The
 * link in the row then needs no stop of its own.
 */
fun AttrsScope<HTMLTableRowElement>.opensOnEnter(onOpen: () -> Unit) {
    tabIndex(0)
    onKeyDown { event ->
        if (event.key == "Enter" && event.target == event.nativeEvent.currentTarget) {
            onOpen()
        }
    }
}

/** The "All / Problems" switch of a list. */
@Composable
fun ProblemsFilter(filter: ProblemsFilterModel) {
    Div({
        classes("segmented")
        attr("role", "group")
        attr("aria-label", "Show")
    }) {
        Button({
            if (!filter.problemsOnly) classes("active")
            attr("aria-pressed", (!filter.problemsOnly).toString())
            onClick { filter.onChange(false) }
        }) {
            Text("All ${filter.total}")
        }
        Button({
            if (filter.problemsOnly) classes("active")
            attr("aria-pressed", filter.problemsOnly.toString())
            onClick { filter.onChange(true) }
        }) {
            Text("${filter.label} ${filter.problems}")
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
fun NavLink(
    href: String,
    onNavigate: () -> Unit,
    classNames: List<String> = emptyList(),
    /** False for a link in a row that is itself a stop for the Tab key. */
    tabStop: Boolean = true,
    content: @Composable () -> Unit,
) {
    A(href = href, {
        if (classNames.isNotEmpty()) classes(classNames)
        if (!tabStop) tabIndex(-1)
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
