package homegap.web.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import homegap.presentation.routing.Location
import homegap.presentation.ui.FieldKind
import homegap.presentation.zone.CAA_TAGS
import homegap.presentation.zone.RecordField
import homegap.presentation.zone.RecordFormModel
import homegap.presentation.zone.RecordKind
import homegap.presentation.zone.ZoneRecordRowModel
import homegap.presentation.zone.ZoneRecordsScreenModel
import homegap.web.routing.HashRouter
import homegap.web.ui.*
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.selected
import org.jetbrains.compose.web.dom.*

/** Every record of one zone in one list, with the form for a record of the user. */
@Composable
fun ZoneRecordsScreen(
    model: ZoneRecordsScreenModel,
    viewport: Viewport,
    onOpen: (Location) -> Unit,
) {
    model.notice?.let { notice ->
        Div({ classes("banner", "info") }) { Text(notice) }
    }
    Div({ classes("filters", "zone-bar") }) {
        Label(attrs = { classes("zone-switch") }) {
            Span({ classes("caption") }) { Text("Zone") }
            Select({
                attr("aria-label", "Zone")
                onChange { event ->
                    model.zones.firstOrNull { it.name == event.value }?.let { onOpen(it.location) }
                }
            }) {
                model.zones.forEach { zone ->
                    Option(
                        value = zone.name,
                        attrs = {
                            if (zone.selected) {
                                selected()
                            }
                        },
                    ) {
                        Text(zone.label)
                    }
                }
            }
        }
        Label(attrs = { classes("filter-field") }) {
            Icon(Icons.SEARCH, size = 14)
            Input(InputType.Search) {
                value(model.query)
                attr("placeholder", "Filter by name, type or value")
                attr("aria-label", "Filter records")
                onInput { model.onQuery(it.value) }
            }
        }
        model.add?.let { add ->
            Action(
                action = add,
                onOpen = onOpen,
            )
        }
    }
    Figure(
        title = "Records",
        note = model.counts,
        flush = true,
    ) {
        if (model.noOwn) {
            Div({ classes("zone-none") }) {
                H3 { Text("No records of your own") }
                P({ classes("muted") }) { Text("HomeGAP manages every record in this zone.") }
                model.add?.let { add ->
                    Action(
                        action = add.copy(label = "Add a record"),
                        onOpen = onOpen,
                    )
                }
            }
        }
        if (model.rows.isEmpty() && !model.noOwn) {
            P({ classes("figure-note") }) { Text("Nothing matches the filter.") }
        }
        model.rows.forEach { row ->
            key(row.id) {
                RecordRow(
                    row = row,
                    selected = row.location == model.selected,
                    onOpen = onOpen,
                )
            }
        }
    }
    model.form?.let { form ->
        RecordForm(
            form = form,
            viewport = viewport,
            onOpen = onOpen,
        )
    }
}

@Composable
private fun RecordRow(row: ZoneRecordRowModel, selected: Boolean, onOpen: (Location) -> Unit) {
    val locked = row.kind != RecordKind.Own
    NavLink(
        href = HashRouter.href(row.location),
        onNavigate = { onOpen(row.location) },
        classNames = listOfNotNull(
            "zone-row",
            "locked".takeIf { locked },
            "challenge".takeIf { row.kind == RecordKind.Challenge },
            "selected".takeIf { selected },
        ),
    ) {
        Span({
            classes("zone-lock")
            if (locked) {
                attr("role", "img")
                attr("aria-label", "locked")
            }
        }) {
            if (locked) {
                Icon(Icons.LOCK, size = 13)
            }
        }
        Span({ classes("mono", "zone-name") }) {
            Text(row.name)
            Span({ classes("zone-suffix") }) { Text(row.suffix) }
        }
        Span({ classes("mono", "zone-type") }) { Text(row.type) }
        Span({ classes("zone-value") }) {
            Code { Text(row.value) }
        }
        Span({ classes("mono", "zone-ttl") }) { Text(row.ttl) }
        Span({ classes("zone-purpose") }) { Text(row.purpose) }
    }
}

/** The form for a new record or an edit: a drawer, or a sheet on a phone, with Save and Cancel. */
@Composable
private fun RecordForm(form: RecordFormModel, viewport: Viewport, onOpen: (Location) -> Unit) {
    val input = form.input
    val close = { onOpen(form.close) }
    Overlay(
        label = form.title,
        titleId = "record-form-title",
        style = if (viewport == Viewport.Phone) OverlayStyle.Sheet else OverlayStyle.Drawer,
        onDismiss = close,
    ) {
        Div({ classes("overlay-panel", "form-panel", "record-form") }) {
            Div({ classes("wizard-head") }) {
                Div {
                    Div({ classes("caption") }) { Text("Zone · ${form.zone}") }
                    H2({ id("record-form-title") }) { Text(form.title) }
                }
                IconButton(
                    iconPath = Icons.CLOSE,
                    label = "Cancel",
                    onClick = close,
                )
            }
            Div({ classes("form-body") }) {
                ScrollHint()
                form.refusal?.let { refusal ->
                    P({
                        classes("banner", "error")
                        attr("role", "alert")
                    }) {
                        Text(refusal)
                    }
                }
                Div({ classes("field") }) {
                    Span({ classes("muted") }) { Text("Type") }
                    Div({
                        classes("segmented", "record-types")
                        attr("role", "group")
                        attr("aria-label", "Type")
                    }) {
                        form.types.forEach { type ->
                            Button({
                                attr("type", "button")
                                if (type == input.type) {
                                    classes("active")
                                }
                                attr("aria-pressed", (type == input.type).toString())
                                if (form.typeLocked && type != input.type) {
                                    disabled()
                                }
                                onClick { form.onChange(input.copy(type = type)) }
                            }) {
                                Text(type)
                            }
                        }
                    }
                    if (form.typeLocked) {
                        Span({ classes("field-note") }) { Text("The type of a record is fixed.") }
                    }
                }
                Label {
                    Text("Name")
                    Span({
                        classes("suffix-field")
                        if (RecordField.Name in form.errors) {
                            classes("invalid")
                        }
                    }) {
                        Input(InputType.Text) {
                            value(input.name)
                            attr("placeholder", "@")
                            attr("autocapitalize", "off")
                            attr("autocorrect", "off")
                            spellCheck(false)
                            classes("mono")
                            onInput { form.onChange(input.copy(name = it.value)) }
                        }
                        Span({ classes("mono") }) { Text(".${form.zone}") }
                    }
                    form.errors[RecordField.Name]?.let { Span({ classes("field-note", "error") }) { Text(it) } }
                    Span({ classes("field-note", "mono") }) { Text(form.fullName) }
                    form.nameNote?.let { Span({ classes("field-note") }) { Text(it) } }
                }
                if (input.type == "CAA") {
                    Label {
                        Text("Tag")
                        Select({
                            classes("select", "mono")
                            onChange { event -> event.value?.let { form.onChange(input.copy(tag = it)) } }
                        }) {
                            CAA_TAGS.forEach { tag ->
                                Option(
                                    value = tag,
                                    attrs = {
                                        if (tag == input.tag) {
                                            selected()
                                        }
                                    },
                                ) {
                                    Text(tag)
                                }
                            }
                        }
                    }
                }
                if (input.type == "TXT") {
                    Label {
                        Text(form.valueLabel)
                        TextArea(input.value) {
                            attr("placeholder", form.valuePlaceholder)
                            attr("rows", "4")
                            spellCheck(false)
                            classes("mono")
                            if (RecordField.Value in form.errors) {
                                classes("invalid")
                            }
                            onInput { form.onChange(input.copy(value = it.value)) }
                        }
                        form.errors[RecordField.Value]?.let { Span({ classes("field-note", "error") }) { Text(it) } }
                    }
                } else {
                    TextField(
                        label = form.valueLabel,
                        value = input.value,
                        placeholder = form.valuePlaceholder,
                        kind = FieldKind.Host,
                        error = form.errors[RecordField.Value],
                        onChange = { form.onChange(input.copy(value = it)) },
                    )
                }
                if (input.type == "MX") {
                    Div({ classes("field-short") }) {
                        TextField(
                            label = "Priority",
                            value = input.priority,
                            placeholder = "10",
                            kind = FieldKind.Number,
                            error = form.errors[RecordField.Priority],
                            onChange = { form.onChange(input.copy(priority = it)) },
                        )
                    }
                }
                if (input.type == "CAA") {
                    Div({ classes("field-short") }) {
                        TextField(
                            label = "Flags",
                            value = input.flags,
                            placeholder = "0",
                            kind = FieldKind.Number,
                            error = form.errors[RecordField.Flags],
                            onChange = { form.onChange(input.copy(flags = it)) },
                        )
                    }
                }
                Label(attrs = { classes("field-short") }) {
                    Text("TTL")
                    Select({
                        classes("select")
                        onChange { event -> event.value?.toIntOrNull()?.let { form.onChange(input.copy(ttlSeconds = it)) } }
                    }) {
                        form.ttlChoices.forEach { choice ->
                            Option(
                                value = choice.seconds.toString(),
                                attrs = {
                                    if (choice.seconds == input.ttlSeconds) {
                                        selected()
                                    }
                                },
                            ) {
                                Text(choice.label)
                            }
                        }
                    }
                }
            }
            Div({ classes("form-actions") }) {
                ActionButton(
                    text = "Cancel",
                    enabled = !form.saving,
                    onClick = close,
                )
                ActionButton(
                    text = form.saveLabel,
                    style = ButtonStyle.Primary,
                    enabled = !form.saving,
                    onClick = form.onSave,
                )
            }
        }
    }
}
