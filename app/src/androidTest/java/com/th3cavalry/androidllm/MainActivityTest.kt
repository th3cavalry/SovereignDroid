package com.th3cavalry.androidllm

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Espresso instrumented tests for [MainActivity].
 *
 * These tests verify key UI interactions without requiring an actual LLM
 * backend — they validate the chat input, button states, and navigation elements.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun chatInputAndSendButtonAreVisible() {
        onView(withId(R.id.etInput)).check(matches(isDisplayed()))
        onView(withId(R.id.btnSend)).check(matches(isDisplayed()))
    }

    @Test
    fun typingTextEnablesSendButton() {
        onView(withId(R.id.etInput))
            .perform(typeText("Hello"), closeSoftKeyboard())
        onView(withId(R.id.btnSend)).check(matches(isEnabled()))
    }

    @Test
    fun sendButtonClearsInput() {
        onView(withId(R.id.etInput))
            .perform(typeText("Test message"), closeSoftKeyboard())
        onView(withId(R.id.btnSend)).perform(click())
        onView(withId(R.id.etInput)).check(matches(withText("")))
    }

    @Test
    fun recyclerViewIsDisplayed() {
        onView(withId(R.id.recyclerView)).check(matches(isDisplayed()))
    }

    @Test
    fun toolbarIsDisplayed() {
        onView(withId(R.id.toolbar)).check(matches(isDisplayed()))
    }

    @Test
    fun attachButtonIsDisplayed() {
        onView(withId(R.id.btnAttach)).check(matches(isDisplayed()))
    }

    @Test
    fun overflowMenuOpens() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        openActionBarOverflowOrOptionsMenu(context)
        // Settings should appear in the overflow menu
        onView(withText(R.string.settings)).check(matches(isDisplayed()))
    }
}
