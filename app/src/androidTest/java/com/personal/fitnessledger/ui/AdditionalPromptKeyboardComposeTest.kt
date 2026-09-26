package com.personal.fitnessledger.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.fitnessledger.ui.theme.FitnessLedgerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Uses the real IME: a Compose-only click can otherwise pass through an obscuring keyboard. */
@SdkSuppress(minSdkVersion = 29)
class AdditionalPromptKeyboardComposeTest {
    @get:Rule val rule = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    @Test fun saveAndSecondaryConfirmationActionsStayAboveTheRealKeyboard() {
        val previous = shell("settings get secure show_ime_with_hard_keyboard").trim()
        val previousAccessibilityFlags = automation.serviceInfo.flags
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            shell("settings put secure show_ime_with_hard_keyboard 1")
            var saved: String? = null
            rule.setContent { FitnessLedgerTheme(darkTheme = false) {
                AnalysisAdditionalPromptCard(AppUiState(analysisAdditionalPrompt = "原有合成说明")) { text, done ->
                    saved = text
                    done(true)
                }
            } }
            rule.onNodeWithTag("edit-additional-prompt").performClick()
            rule.onNodeWithTag("additional-prompt-input").performScrollTo().performClick()
            rule.onNodeWithTag("additional-prompt-input").performTextReplacement("键盘下的新合成说明")
            showAndWaitForKeyboard()
            assertActionAboveKeyboard("save-additional-prompt")
            assertActionAboveKeyboard("cancel-additional-prompt")
            tapActionThroughSystem("save-additional-prompt")
            rule.runOnIdle { assertEquals("键盘下的新合成说明", saved) }

            rule.onNodeWithTag("edit-additional-prompt").performClick()
            rule.onNodeWithTag("additional-prompt-input").performScrollTo().performClick()
            rule.onNodeWithTag("additional-prompt-input").performTextReplacement("未保存的合成说明")
            showAndWaitForKeyboard()
            tapActionThroughSystem("cancel-additional-prompt")
            assertActionAboveKeyboard("confirm-additional-prompt-discard")
            assertActionAboveKeyboard("keep-editing-additional-prompt")
            tapActionThroughSystem("keep-editing-additional-prompt")

            rule.onNodeWithTag("additional-prompt-input").performScrollTo().performClick()
            rule.onNodeWithTag("additional-prompt-input").performTextReplacement("")
            showAndWaitForKeyboard()
            tapActionThroughSystem("save-additional-prompt")
            assertActionAboveKeyboard("confirm-clear-additional-prompt")
            assertActionAboveKeyboard("cancel-clear-additional-prompt")
            tapActionThroughSystem("cancel-clear-additional-prompt")
        } finally {
            shell(if (previous == "0" || previous == "1")
                "settings put secure show_ime_with_hard_keyboard $previous"
                else "settings delete secure show_ime_with_hard_keyboard")
            automation.serviceInfo = automation.serviceInfo.apply { flags = previousAccessibilityFlags }
        }
    }

    private fun showAndWaitForKeyboard() {
        rule.onNodeWithTag("additional-prompt-input").assertIsFocused()
        // An emulator hardware-keyboard policy change can leave the initial implicit show stale.
        // Request the real IME against the actual focused editor, never a synthetic text service.
        instrumentation.runOnMainSync {
            val root = WindowInspector.getGlobalWindowViews().single { it.hasWindowFocus() }
            val editor = checkNotNull(root.findFocus())
            val inputMethod = editor.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethod.hideSoftInputFromWindow(editor.windowToken, 0)
            editor.post { inputMethod.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
        }
        // A dialog above the keyboard may correctly have ime.bottom == 0. Observe the real
        // system IME window instead of confusing visibility with overlap of the dialog window.
        rule.waitUntil(timeoutMillis = 10_000) { keyboardBounds() != null }
    }

    private fun assertActionAboveKeyboard(tag: String) {
        rule.waitForIdle()
        val (bounds, window) = actionScreenBounds(tag)
        val keyboard = keyboardBounds()
        // Secondary dialogs may naturally dismiss the IME when taking focus. If it remains,
        // their actions must still be above its actual screen rectangle, not merely in semantics.
        val visibleBottom = minOf(window.bottom, keyboard?.top ?: window.bottom)
        assertTrue("$tag must stay visible: action=$bounds window=$window keyboard=$keyboard",
            bounds.width() > 0 && bounds.height() > 0 && bounds.top >= window.top && bounds.bottom <= visibleBottom + 1f)
    }

    private fun keyboardBounds(): Rect? = automation.windows
        .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        ?.let { window -> Rect().apply { window.getBoundsInScreen(this) }.takeUnless { it.isEmpty } }

    private fun actionScreenBounds(tag: String): Pair<RectF, Rect> {
        val bounds = rule.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        var originX = 0
        var originY = 0
        val window = Rect()
        instrumentation.runOnMainSync {
            val root = WindowInspector.getGlobalWindowViews().single { it.hasWindowFocus() }
            val screen = IntArray(2).also(root::getLocationOnScreen)
            val local = IntArray(2).also(root::getLocationInWindow)
            originX = screen[0] - local[0]
            originY = screen[1] - local[1]
            window.set(screen[0], screen[1], screen[0] + root.width, screen[1] + root.height)
        }
        return RectF(bounds.left + originX, bounds.top + originY, bounds.right + originX, bounds.bottom + originY) to window
    }

    private fun tapActionThroughSystem(tag: String) {
        assertActionAboveKeyboard(tag)
        val bounds = actionScreenBounds(tag).first
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                bounds.centerX(), bounds.centerY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue("System touch injection failed for $tag", automation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        rule.waitForIdle()
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }
}
