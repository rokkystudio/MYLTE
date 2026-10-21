package com.rokkystudio.mylte

import android.content.Context
import android.content.res.Configuration
import badfox.logic.neoui.NeoThemeMode
import badfox.logic.neoui.NeoTopBarView
import badfox.logic.neoui.NeoUiSettings

/**
 * Stores, applies and binds standard NeoUI interface settings for MYLTE.
 */
class UiSettings(context: Context) {
    private val uiSettings = NeoUiSettings(
        context = context,
        preferencesName = PREFERENCES_NAME,
        defaultTheme = NeoThemeMode.DARK,
        defaultLanguageTag = null,
    )

    fun getTheme(): AppTheme = uiSettings.getTheme()

    fun getLanguage(): String = uiSettings.getLanguage()

    fun applyAppCompatNightMode() {
        uiSettings.applyAppCompatNightMode()
    }

    fun applyToConfiguration(configuration: Configuration) {
        uiSettings.applyToConfiguration(configuration)
    }

    fun bindTopBarUi(topBar: NeoTopBarView, onChanged: () -> Unit) {
        uiSettings.bindTopBar(
            topBar = topBar,
            languageTags = listOf("en", "ru"),
            onChanged = onChanged,
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "mylte_ui"
    }
}

typealias AppTheme = NeoThemeMode