package com.th3cavalry.androidllm

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.th3cavalry.androidllm.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding
    private var appliedThemeIndex: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedThemeIndex = ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.tvVersion.text = BuildConfig.VERSION_NAME
    }

    override fun onResume() {
        super.onResume()
        ThemeHelper.recreateIfNeeded(this, appliedThemeIndex)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
