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
 * When the theme is changed in Settings, [markThemeChanged] is called so that other
 * activities know to recreate themselves in [onResume].
 */
object ThemeHelper {

    /**
     * Set to `true` by [markThemeChanged]; cleared when an activity observes the change
     * and calls [recreateIfNeeded]. Activities check this in `onResume` to re-inflate with
     * the new colors.
     */
    @Volatile
    var themeChanged: Boolean = false
        private set

    fun themeResId(context: Context): Int {
        return when (Prefs.getInt(context, Prefs.KEY_COLOR_THEME, 0)) {
            1    -> R.style.Theme_AndroidLLM_Blue
            2    -> R.style.Theme_AndroidLLM_Green
            3    -> R.style.Theme_AndroidLLM_Orange
            else -> R.style.Theme_AndroidLLM
        }
    }

    /**
     * Applies the stored color theme to [activity]. Call this **before** [AppCompatActivity.super.onCreate].
     */
    fun applyTheme(activity: AppCompatActivity) {
        activity.setTheme(themeResId(activity))
    }

    /** Signals that the user changed the theme; other activities should recreate on resume. */
    fun markThemeChanged() {
        themeChanged = true
    }

    /**
     * If the theme was changed since this activity was last created, recreates [activity]
     * so it is re-inflated with the new colors. Call this in `onResume`.
     *
     * The flag is cleared inside this method so each activity recreates at most once per change.
     */
    fun recreateIfNeeded(activity: AppCompatActivity) {
        if (themeChanged) {
            themeChanged = false
            activity.recreate()
        }
    }
}
