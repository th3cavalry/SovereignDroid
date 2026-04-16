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
     * Applies the stored color theme to [activity]. Call this **before** [AppCompatActivity.super.onCreate].
     */
    fun applyTheme(activity: AppCompatActivity) {
        activity.setTheme(themeResId(activity))
    }
}
