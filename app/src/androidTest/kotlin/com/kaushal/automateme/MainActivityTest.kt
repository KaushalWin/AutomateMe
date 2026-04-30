package com.kaushal.automateme

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.clearText
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented integration tests for [MainActivity].
 *
 * These run on a real device or emulator. The overlay and accessibility service
 * permissions are NOT granted here; tests verify the UI renders and basic
 * interactions work correctly.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    // -------------------------------------------------------------------------
    // Launch
    // -------------------------------------------------------------------------

    @Test
    fun activity_launches_without_crash() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity)
            }
        }
    }

    // -------------------------------------------------------------------------
    // UI element visibility
    // -------------------------------------------------------------------------

    @Test
    fun apiKey_editText_isDisplayed() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.etApiKey)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun task_editText_isDisplayed() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.etTask)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun saveApiKey_button_isDisplayed() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.btnSaveApiKey)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun startAutomation_button_isDisplayed() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.btnStartAutomation)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun stopAutomation_button_isDisplayed() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.btnStopAutomation)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun accessibilityStatus_textView_isDisplayed() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.tvAccessibilityStatus)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun overlayStatus_textView_isDisplayed() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.tvOverlayStatus)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun log_textView_isDisplayed() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.tvLog)).check(matches(isDisplayed()))
        }
    }

    // -------------------------------------------------------------------------
    // API key interactions
    // -------------------------------------------------------------------------

    @Test
    fun saveApiKey_withBlankInput_doesNotCrash() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.etApiKey)).perform(clearText())
            onView(withId(R.id.btnSaveApiKey)).perform(click())
            // Activity should still be visible
            onView(withId(R.id.etApiKey)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun saveApiKey_withValidKey_keepsKeyInField() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.etApiKey)).perform(
                clearText(),
                typeText("sk-test-key-99999"),
                closeSoftKeyboard()
            )
            onView(withId(R.id.btnSaveApiKey)).perform(click())
            onView(withId(R.id.etApiKey)).check(matches(withText("sk-test-key-99999")))
        }
    }

    // -------------------------------------------------------------------------
    // Enable Accessibility button
    // -------------------------------------------------------------------------

    @Test
    fun enableAccessibility_button_isEnabled() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.btnEnableAccessibility)).check(matches(isEnabled()))
        }
    }

    // -------------------------------------------------------------------------
    // Start automation without pre-requisites
    // -------------------------------------------------------------------------

    @Test
    fun startAutomation_withoutApiKey_doesNotCrash() {
        ActivityScenario.launch(MainActivity::class.java).use {
            // Clear any saved key
            onView(withId(R.id.etApiKey)).perform(clearText(), closeSoftKeyboard())
            onView(withId(R.id.btnStartAutomation)).perform(click())
            // Activity must still be visible
            onView(withId(R.id.btnStartAutomation)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun startAutomation_withApiKeyButNoTask_doesNotCrash() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.etApiKey)).perform(
                clearText(),
                typeText("sk-test"),
                closeSoftKeyboard()
            )
            onView(withId(R.id.btnSaveApiKey)).perform(click())
            onView(withId(R.id.etTask)).perform(clearText(), closeSoftKeyboard())
            onView(withId(R.id.btnStartAutomation)).perform(click())
            onView(withId(R.id.btnStartAutomation)).check(matches(isDisplayed()))
        }
    }
}
