package com.luke.tracks.tv

import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.luke.tracks.ui.theme.TracksBrand
import com.luke.tracks.ui.tv.components.TvButton
import com.luke.tracks.ui.tv.components.TvTextField
import com.luke.tracks.ui.tv.theme.TvTracksTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The TV text field opens the system keyboard on OK, Enter or a tap, never on focus alone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvTextFieldTest {
    @get:Rule val compose = createComposeRule()

    private val ime get() = shadowOf(ApplicationProvider.getApplicationContext<android.content.Context>().getSystemService(InputMethodManager::class.java))
    private val field = TextFieldState("blue")
    private val outer = FocusRequester()
    private var searched by mutableIntStateOf(0)
    private lateinit var keyboard: SoftwareKeyboardController

    private fun show() {
        compose.setContent {
            keyboard = LocalSoftwareKeyboardController.current!!
            TvTracksTheme(dark = false, dynamic = false, seed = TracksBrand) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvTextField(
                        field, label = "Search", placeholder = "Songs",
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        onKeyboardAction = { searched++ },
                        modifier = Modifier.width(400.dp).focusRequester(outer),
                    )
                    TvButton("Result", onClick = {})
                }
            }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test fun focusAloneLeavesTheKeyboardClosedAndOkOpensItWithTheCursorAtTheEnd() {
        show()
        field.edit { selection = TextRange(0) }
        compose.runOnIdle { outer.requestFocus() }
        compose.waitForIdle()
        assertFalse("focus alone opened the keyboard", ime.isSoftInputVisible)
        compose.onNodeWithContentDescription("Search").assertIsNotFocused()

        press(Key.DirectionCenter)
        compose.onNodeWithContentDescription("Search").assertIsFocused()
        assertTrue("OK did not open the keyboard", ime.isSoftInputVisible)
        assertEquals(TextRange(4), field.selection)

        compose.onNodeWithContentDescription("Search").performTextInput(" sky")
        assertEquals("blue sky", field.text.toString())
        compose.onNodeWithContentDescription("Search").performImeAction()
        assertEquals(1, searched)
    }

    @Test fun okOpensTheKeyboardAgainAfterBackClosedIt() {
        show()
        compose.runOnIdle { outer.requestFocus() }
        press(Key.DirectionCenter)
        assertTrue(ime.isSoftInputVisible)

        // Back closes the system keyboard itself; focus stays on the field.
        compose.runOnIdle { keyboard.hide() }
        compose.waitForIdle()
        assertFalse(ime.isSoftInputVisible)
        compose.onNodeWithContentDescription("Search").assertIsFocused()

        press(Key.DirectionCenter)
        assertTrue("OK did not open the keyboard a second time", ime.isSoftInputVisible)
        compose.onNodeWithContentDescription("Search").assertIsFocused()
    }

    @Test fun enterOpensItToo() {
        show()
        compose.runOnIdle { outer.requestFocus() }
        compose.waitForIdle()
        press(Key.Enter)
        assertTrue(ime.isSoftInputVisible)
        assertEquals(0, searched)
    }

    @Test fun downLeavesTheFieldAndClosesTheKeyboard() {
        show()
        compose.runOnIdle { outer.requestFocus() }
        press(Key.DirectionCenter)
        compose.runOnIdle { keyboard.hide() }
        press(Key.DirectionDown)
        compose.onNodeWithText("Result").assertIsFocused()
        compose.onNodeWithContentDescription("Search").assertIsNotFocused()
        assertFalse(ime.isSoftInputVisible)

        press(Key.DirectionUp)
        compose.onNodeWithContentDescription("Search").assertIsNotFocused()
        assertFalse("coming back up opened the keyboard", ime.isSoftInputVisible)
        press(Key.DirectionCenter)
        assertTrue(ime.isSoftInputVisible)
    }

    @Test fun aTapFocusesTheFieldAndOpensTheKeyboard() {
        show()
        compose.onNodeWithContentDescription("Search").performTouchInput { click() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Search").assertIsFocused()
        assertTrue("a tap did not open the keyboard", ime.isSoftInputVisible)
    }
}
