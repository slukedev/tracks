package com.luke.tracks.ui.tv.components

import android.view.InputDevice
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luke.tracks.ui.tv.theme.TvMotion
import androidx.compose.material3.MaterialTheme as M3Theme

/**
 * A text field for the remote and for touch. The remote focuses it like any control without
 * opening the keyboard; OK or Enter then opens the system keyboard with the cursor at the end,
 * and a tap opens it straight away. Back closes the keyboard and leaves focus here; Up and Down
 * move on, as do Left and Right past the ends of the text.
 *
 * [modifier] goes on the control the remote focuses, so a focus requester there lands on it
 * without opening the keyboard.
 */
@Composable
fun TvTextField(
    state: TextFieldState,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardAction: KeyboardActionHandler? = null,
    inputTransformation: InputTransformation? = null,
    leadingIcon: ImageVector? = null,
    shape: Shape = CircleShape,
    minHeight: Dp = 56.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val editor = remember { FocusRequester() }
    var resting by remember { mutableStateOf(false) }
    var lit by remember { mutableStateOf(false) }
    var openedOnDown by remember { mutableStateOf(false) }
    // BasicTextField starts its input session, the only thing the keyboard can attach to, when
    // the field itself gains focus or is tapped. So the remote focuses this outer control, which
    // starts nothing, and OK hands focus to the field inside.
    Box(
        modifier
            .onFocusChanged {
                resting = it.isFocused
                lit = it.hasFocus
            }
            .onKeyEvent { event ->
                if (!event.isOpenKey) return@onKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> if (resting) {
                        state.edit { placeCursorAtEnd() }
                        openedOnDown = editor.requestFocus()
                        openedOnDown
                    } else false
                    KeyEventType.KeyUp -> openedOnDown.also { openedOnDown = false }
                    else -> false
                }
            }
            .focusable(),
    ) {
        BasicTextField(
            state = state,
            inputTransformation = inputTransformation,
            lineLimits = TextFieldLineLimits.SingleLine,
            textStyle = MaterialTheme.typography.titleLarge.copy(color = scheme.onSurface),
            cursorBrush = SolidColor(scheme.primary),
            keyboardOptions = keyboardOptions.copy(showKeyboardOnFocus = true),
            onKeyboardAction = onKeyboardAction,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(editor)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        // After Back has closed it, OK opens the keyboard again in the same session.
                        event.isOpenKey && !event.isFromTypingKeyboard -> { keyboard?.show(); true }
                        event.key == Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                        event.key == Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                        else -> false
                    }
                }
                .semantics { contentDescription = label },
            decorator = { inner ->
                Row(
                    Modifier
                        .heightIn(min = minHeight)
                        .background(if (lit) M3Theme.colorScheme.surfaceContainerHighest else M3Theme.colorScheme.surfaceContainerHigh, shape)
                        .border(BorderStroke(if (lit) TvMotion.FocusBorderWidth else 0.dp, if (lit) scheme.primary else scheme.surface.copy(alpha = 0f)), shape)
                        .padding(horizontal = 22.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (leadingIcon != null) {
                        Icon(leadingIcon, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(14.dp))
                    }
                    Box(Modifier.weight(1f)) {
                        if (state.text.isEmpty()) Text(placeholder, style = MaterialTheme.typography.titleLarge, color = scheme.onSurfaceVariant, maxLines = 1)
                        inner()
                    }
                }
            },
        )
    }
}

private val KeyEvent.isOpenKey get() = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

/** A real keyboard's Enter is left to the field, which submits; a remote's opens the keyboard. */
private val KeyEvent.isFromTypingKeyboard: Boolean
    get() = nativeKeyEvent.device?.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC && key != Key.DirectionCenter
