package com.th3cavalry.androidllm

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasFocus
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Espresso tests for the home screen widget launch path.
 * Verifies that the EXTRA_FOCUS_INPUT intent focuses the input field.
 */
@RunWith(AndroidJUnit4::class)
class WidgetLaunchTest {

    private val widgetIntent = Intent(
        ApplicationProvider.getApplicationContext(),
        MainActivity::class.java
    ).apply {
        putExtra(QuickPromptWidget.EXTRA_FOCUS_INPUT, true)
    }

    @get:Rule
    val activityRule = ActivityScenarioRule<MainActivity>(widgetIntent)

    @Test
    fun widgetLaunchFocusesInput() {
        onView(withId(R.id.etInput)).check(matches(hasFocus()))
    }
}
