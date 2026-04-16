package com.th3cavalry.androidllm

import android.content.Context
import androidx.appcompat.app.AppCompatActivity

/**
 * Helper for applying the user-selected color theme.
 *
 * The theme index is stored in [Prefs.KEY_COLOR_THEME]:
 * - 0 → Purple / default ([R.style.Theme_AndroidLLM])
 * - 1 → Blue            ([R.style.Theme_AndroidLLM_Blue])
 * - 2 → Green           ([R.style.Theme_AndroidLLM_Green])
 * - 3 → Orange          ([R.style.Theme_AndroidLLM_Orange])
 *
 * Each [AppCompatActivity] should call [applyTheme] before [AppCompatActivity.super.onCreate]
 * so that the correct theme is in place when the layout is inflated.
 *
 * ### Change propagation
 * Instead of a shared mutable flag (which races when multiple activities are alive), each
 * activity stores the theme index it was inflated with in [currentThemeIndex].  In `onResume`
 * it calls [recreateIfNeeded], which compares the stored index with the current Prefs value
 * and calls [AppCompatActivity.recreate] only when they differ.  This is safe across any
 * number of concurrent activities and survives process death because the truth lives in Prefs.
 */
object ThemeHelper {

    fun themeResId(context: Context): Int {
        return when (Prefs.getInt(context, Prefs.KEY_COLOR_THEME, 0)) {
            1    -> R.style.Theme_AndroidLLM_Blue
            2    -> R.style.Theme_AndroidLLM_Green
            3    -> R.style.Theme_AndroidLLM_Orange
            else -> R.style.Theme_AndroidLLM
        }
    }

    /**
     * Applies the stored color theme to [activity] and returns the applied theme index.
     * Call this **before** [AppCompatActivity.super.onCreate] and store the returned value:
     * ```
     * private var appliedThemeIndex = ThemeHelper.applyTheme(this)
     * ```
     */
    fun applyTheme(activity: AppCompatActivity): Int {
        val index = Prefs.getInt(activity, Prefs.KEY_COLOR_THEME, 0)
        activity.setTheme(themeResId(activity))
        return index
    }

    /**
     * Call in `onResume`. Recreates [activity] if the theme stored in Prefs differs from
     * [currentThemeIndex] (the value returned by [applyTheme] at creation time).
     */
    fun recreateIfNeeded(activity: AppCompatActivity, currentThemeIndex: Int) {
        val latest = Prefs.getInt(activity, Prefs.KEY_COLOR_THEME, 0)
        if (latest != currentThemeIndex) {
            activity.recreate()
        }
    }
}
