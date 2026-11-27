package com.luke.tracks.ui.tv.link

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luke.tracks.ui.tv.components.TvButton
import com.luke.tracks.ui.tv.components.TvButtonStyle
import com.luke.tracks.ui.tv.components.TvListRow
import com.luke.tracks.ui.tv.theme.TvMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import androidx.compose.material3.MaterialTheme as M3Theme

private sealed interface Phase {
    data object Choose : Phase
    data object Address : Phase
    data class Link(val server: LinkServer) : Phase
}

/**
 * Linking this TV to Trackside: pick a server found nearby or type its address, then show the
 * code and QR until a phone approves it. Polls only while on screen. [onLinked] runs a beat
 * after success, so the check registers. [claimFocus] moves focus in as each step opens;
 * off where the flow sits beside other focusable things (Settings) until it has focus.
 */
@Composable
fun TvLinkFlow(
    onLinked: () -> Unit,
    modifier: Modifier = Modifier,
    claimFocus: Boolean = true,
    footer: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val directory = LocalLinkDirectory.current ?: remember(context) { AppLinkDirectory(context) }
    val linker = LocalTvLinker.current
    var phase by remember { mutableStateOf<Phase>(Phase.Choose) }
    var inside by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val take = claimFocus || inside
    LaunchedEffect(phase) {
        if (!take) return@LaunchedEffect
        repeat(8) {
            withFrameNanos { }
            if (first.requestFocus()) return@LaunchedEffect
        }
    }

    AnimatedContent(
        targetState = phase,
        transitionSpec = { fadeIn(tween(TvMotion.ScreenMs, easing = TvMotion.EaseOut)) togetherWith fadeOut(tween(90)) },
        contentKey = { it::class },
        label = "link",
        modifier = modifier.onFocusChanged { inside = it.hasFocus }.focusGroup(),
    ) { shown ->
        when (shown) {
            Phase.Choose -> ChooseServer(
                nearby = directory.nearby,
                first = first,
                onPick = { phase = Phase.Link(it) },
                onType = { phase = Phase.Address },
                footer = footer,
            )
            Phase.Address -> TypeAddress(
                lookUp = directory::lookUp,
                first = first,
                onFound = { phase = Phase.Link(it) },
                onCancel = { phase = Phase.Choose },
            )
            is Phase.Link -> {
                LinkCode(
                    linker = linker,
                    server = shown.server,
                    first = first,
                    onLinked = onLinked,
                    onOtherServer = { phase = Phase.Choose },
                    footer = footer,
                )
            }
        }
    }
}

@Composable
private fun ChooseServer(
    nearby: Flow<List<LinkServer>>,
    first: FocusRequester,
    onPick: (LinkServer) -> Unit,
    onType: () -> Unit,
    footer: (@Composable () -> Unit)?,
) {
    val found by nearby.collectAsStateWithLifecycle(emptyList())
    ChooseServerContent(found, first, onPick, onType, footer)
}

/** Servers found nearby as rows, then typing an address; a quiet line while none have answered. */
@Composable
fun ChooseServerContent(
    found: List<LinkServer>,
    first: FocusRequester,
    onPick: (LinkServer) -> Unit,
    onType: () -> Unit,
    footer: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (found.isEmpty()) {
            Row(
                Modifier.heightIn(min = 72.dp).padding(horizontal = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp)
                Text("Looking for Trackside…", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        found.take(MaxShown).forEachIndexed { i, server ->
            TvListRow(
                onClick = { onPick(server) },
                headline = server.name,
                supporting = server.url.host,
                leading = { RowIcon(Icons.Rounded.Dns) },
                modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
            )
        }
        TvListRow(
            onClick = onType,
            headline = "Enter Address",
            leading = { RowIcon(Icons.Rounded.Keyboard) },
            modifier = if (found.isEmpty()) Modifier.focusRequester(first) else Modifier,
        )
        footer?.let { Box(Modifier.padding(top = 16.dp)) { it() } }
    }
}

@Composable
private fun RowIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(
        Modifier.size(44.dp).background(M3Theme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = M3Theme.colorScheme.onSecondaryContainer, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun TypeAddress(
    lookUp: suspend (String) -> LinkServer,
    first: FocusRequester,
    onFound: (LinkServer) -> Unit,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<LinkProblem?>(null) }
    val submit = {
        if (!checking && text.isNotBlank()) {
            checking = true
            problem = null
            scope.launch {
                try {
                    onFound(lookUp(text))
                } catch (error: LinkException) {
                    problem = error.problem
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    problem = LinkProblem.Offline
                } finally {
                    checking = false
                }
            }
        }
    }
    TypeAddressContent(text, { text = it.filterNot(Char::isISOControl).take(256) }, checking, problem, first, submit, onCancel)
}

/** The address field, then Continue and Back; a problem says itself under the field. */
@Composable
fun TypeAddressContent(
    text: String,
    onText: (String) -> Unit,
    checking: Boolean,
    problem: LinkProblem?,
    first: FocusRequester,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BasicTextField(
            value = text,
            onValueChange = onText,
            singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { onSubmit() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(first)
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = "Server Address" },
            decorationBox = { field ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp)
                        .background(M3Theme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
                        .border(BorderStroke(if (focused) TvMotion.FocusBorderWidth else 0.dp, if (focused) colors.primary else colors.surface.copy(alpha = 0f)), RoundedCornerShape(16.dp))
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (text.isEmpty()) {
                        Text("music.example.com", style = MaterialTheme.typography.titleLarge, color = colors.onSurfaceVariant.copy(alpha = 0.7f))
                    }
                    field()
                }
            },
        )
        if (problem != null) {
            Text(
                problem.message,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton("Continue", onClick = onSubmit, style = TvButtonStyle.Primary, pending = checking, enabled = text.isNotBlank() || checking)
            TvButton("Back", onClick = onCancel)
        }
    }
}

@Composable
private fun LinkCode(
    linker: TvLinker,
    server: LinkServer,
    first: FocusRequester,
    onLinked: () -> Unit,
    onOtherServer: () -> Unit,
    footer: (@Composable () -> Unit)?,
) {
    var attempt by remember { mutableStateOf(0) }
    val steps = remember(linker, server, attempt) { linkSteps(linker, server) }
    val step by steps.collectAsStateWithLifecycle(LinkStep.Starting)
    val done by rememberUpdatedState(onLinked)
    LaunchedEffect(step) {
        if (step is LinkStep.Linked) {
            delay(LinkedPauseMs)
            done()
        }
    }
    LinkCodeContent(step, server.name, first, onTryAgain = { attempt++ }, onOtherServer = onOtherServer, footer = footer)
}

/**
 * The code to approve on a phone, big, beside its QR, with the server's name and one line on
 * what to do. Failures say why in a sentence, with the way on.
 */
@Composable
fun LinkCodeContent(
    step: LinkStep,
    serverName: String,
    first: FocusRequester,
    onTryAgain: () -> Unit,
    onOtherServer: () -> Unit,
    footer: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val offer = (step as? LinkStep.Showing)?.offer
    Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(QrSize), contentAlignment = Alignment.Center) {
            when (step) {
                is LinkStep.Showing -> step.offer.qrLink()?.let { LinkQr(it, size = QrSize) }
                LinkStep.Starting -> CircularProgressIndicator(Modifier.size(40.dp), color = colors.primary, strokeWidth = 4.dp)
                is LinkStep.Linked -> Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = colors.primary, modifier = Modifier.size(96.dp))
                is LinkStep.Failed -> Box(Modifier.size(QrSize).background(M3Theme.colorScheme.surfaceContainerHigh, RoundedCornerShape(20.dp)))
            }
        }
        Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(serverName, style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when (step) {
                LinkStep.Starting, is LinkStep.Showing -> {
                    Text(
                        offer?.userCode ?: "    -    ",
                        style = MaterialTheme.typography.displayMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp),
                        color = colors.onSurface,
                        maxLines = 1,
                        modifier = Modifier.semantics { offer?.let { contentDescription = it.userCode.toList().joinToString(" ") } },
                    )
                    Text("Scan with your phone, or enter the code in Tracks.", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                }
                is LinkStep.Linked -> Text("Linked", style = MaterialTheme.typography.displaySmall, color = colors.onSurface)
                is LinkStep.Failed -> Text(
                    step.problem.message,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onSurface,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            val retry = (step as? LinkStep.Failed)?.problem?.let { it != LinkProblem.NeedsUpdate } == true
            val again = remember { FocusRequester() }
            var onOther by remember { mutableStateOf(false) }
            // A failure while you wait moves you onto the way forward.
            LaunchedEffect(retry) { if (retry && onOther) again.requestFocus() }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (retry) TvButton("Try Again", onClick = onTryAgain, style = TvButtonStyle.Primary, modifier = Modifier.focusRequester(again))
                if (step !is LinkStep.Linked) {
                    TvButton(
                        "Other Server",
                        onClick = onOtherServer,
                        modifier = Modifier.focusRequester(first).onFocusChanged { onOther = it.isFocused },
                    )
                }
            }
            footer?.let { Box(Modifier.padding(top = 6.dp)) { it() } }
        }
    }
}

private val QrSize = 200.dp
private const val MaxShown = 6
private const val LinkedPauseMs = 700L
