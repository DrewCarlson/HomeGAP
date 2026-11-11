package homegap.web.ui

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.AttrsScope
import org.jetbrains.compose.web.dom.ContentBuilder
import org.jetbrains.compose.web.dom.ElementBuilder
import org.jetbrains.compose.web.dom.TagElement
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement

// HTML elements Compose HTML has no function for.

@Composable
fun Dl(attrs: (AttrsScope<HTMLElement>.() -> Unit)? = null, content: ContentBuilder<HTMLElement>? = null) =
    TagElement(elementBuilder = DL, applyAttrs = attrs, content = content)

@Composable
fun Dt(attrs: (AttrsScope<HTMLElement>.() -> Unit)? = null, content: ContentBuilder<HTMLElement>? = null) =
    TagElement(elementBuilder = DT, applyAttrs = attrs, content = content)

@Composable
fun Dd(attrs: (AttrsScope<HTMLElement>.() -> Unit)? = null, content: ContentBuilder<HTMLElement>? = null) =
    TagElement(elementBuilder = DD, applyAttrs = attrs, content = content)

/** A native disclosure: it opens and closes without any state of ours. */
@Composable
fun Details(attrs: (AttrsScope<HTMLElement>.() -> Unit)? = null, content: ContentBuilder<HTMLElement>? = null) =
    TagElement(elementBuilder = DETAILS, applyAttrs = attrs, content = content)

@Composable
fun Summary(attrs: (AttrsScope<HTMLElement>.() -> Unit)? = null, content: ContentBuilder<HTMLElement>? = null) =
    TagElement(elementBuilder = SUMMARY, applyAttrs = attrs, content = content)

@Composable
fun Kbd(attrs: (AttrsScope<HTMLElement>.() -> Unit)? = null, content: ContentBuilder<HTMLElement>? = null) =
    TagElement(elementBuilder = KBD, applyAttrs = attrs, content = content)

/** A line-break opportunity that adds no character, so copied text stays intact. */
@Composable
fun Wbr() = TagElement<HTMLElement>(elementBuilder = WBR, applyAttrs = null, content = null)

/** [name] with a line-break opportunity after each dot, so host names wrap between labels. */
@Composable
fun HostName(name: String) {
    name.split('.').forEachIndexed { index, label ->
        if (index > 0) {
            Text(".")
            Wbr()
        }
        Text(label)
    }
}

private val DL = ElementBuilder.createBuilder<HTMLElement>("dl")
private val DT = ElementBuilder.createBuilder<HTMLElement>("dt")
private val DD = ElementBuilder.createBuilder<HTMLElement>("dd")
private val DETAILS = ElementBuilder.createBuilder<HTMLElement>("details")
private val SUMMARY = ElementBuilder.createBuilder<HTMLElement>("summary")
private val KBD = ElementBuilder.createBuilder<HTMLElement>("kbd")
private val WBR = ElementBuilder.createBuilder<HTMLElement>("wbr")
