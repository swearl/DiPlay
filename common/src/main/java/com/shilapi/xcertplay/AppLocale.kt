// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import com.shilapi.xcertplay.host.R
import java.util.Locale

/** 应用语言偏好，通过持久化的 Context 覆盖实现（API 19 无平台级应用语言）。 */
object AppLocale {
    const val SYSTEM = "system"
    const val ENGLISH = "en"
    const val SIMPLIFIED_CHINESE = "zh"
    const val TRADITIONAL_CHINESE = "zh-TW"
    const val ARABIC = "ar"
    const val RUSSIAN = "ru"
    const val SPANISH = "es"
    const val UKRAINIAN = "uk"
    const val JAPANESE = "ja"
    const val KOREAN = "ko"

    const val ITALIAN = "it"
    const val TURKISH = "tr"

    val ALL = listOf(SYSTEM, ENGLISH, SIMPLIFIED_CHINESE, TRADITIONAL_CHINESE, ARABIC, RUSSIAN, SPANISH, UKRAINIAN, JAPANESE, KOREAN, ITALIAN, TURKISH)

    private const val PREFS = "diplay"
    private const val KEY_LANGUAGE = "app_language"

    fun preference(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, SYSTEM)?.takeIf { it in ALL } ?: SYSTEM
    }

    fun save(context: Context, language: String) {
        require(language in ALL)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LANGUAGE, language).apply()
    }

    /** 通过 Context 配置覆盖应用语言（API 19 无系统级应用语言设置）。 */
    fun wrap(context: Context): Context {
        val locale = locale(preference(context)) ?: return context
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return context.createConfigurationContext(configuration)
    }

    /**
     * 部分车机在 [wrap] 之后会把系统语言重新写回 Activity 的资源里，需要再写一次所选语言；
     * 返回 true 表示实际执行了写入。系统默认时写回系统语言，因为进程保留着上次修改后的资源。
     */
    @Suppress("DEPRECATION")
    fun enforce(context: Context): Boolean {
        val locale = locale(preference(context)) ?: Resources.getSystem().configuration.locale
        val resources = context.resources
        if (resources.configuration.locale == locale) return false
        val configuration = Configuration(resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        resources.updateConfiguration(configuration, resources.displayMetrics)
        return true
    }

    fun showPicker(activity: Activity) {
        var selected = ALL.indexOf(preference(activity)).coerceAtLeast(0)
        activity.appDialogBuilder()
            .setTitle(R.string.language_app_language)
            .setSingleChoiceItems(ALL.map { displayName(activity, it) }.toTypedArray(), selected) { _, index ->
                selected = index
            }
            .setPositiveButton(R.string.language_apply) { _, _ ->
                val next = ALL[selected]
                if (next != preference(activity)) {
                    save(activity, next)
                    activity.recreate()
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    /** Names stay in their native form for every language; only "system default" is localized. */
    fun displayName(context: Context, language: String): String = when (language) {
        SYSTEM -> context.getString(R.string.language_system_default)
        ENGLISH -> "English"
        SIMPLIFIED_CHINESE -> "简体中文"
        TRADITIONAL_CHINESE -> "繁體中文"
        ARABIC -> "العربية"
        RUSSIAN -> "Русский"
        SPANISH -> "Español"
        UKRAINIAN -> "Українська"
        JAPANESE -> "日本語"
        KOREAN -> "한국어"
        ITALIAN -> "Italiano"
        TURKISH -> "Türkçe"
        else -> language
    }

    private fun locale(language: String): Locale? = when (language) {
        ENGLISH -> Locale.ENGLISH
        SIMPLIFIED_CHINESE -> Locale.SIMPLIFIED_CHINESE
        TRADITIONAL_CHINESE -> Locale.TRADITIONAL_CHINESE
        ARABIC -> Locale("ar")
        RUSSIAN -> Locale("ru")
        SPANISH -> Locale("es")
        UKRAINIAN -> Locale("uk")
        JAPANESE -> Locale.JAPANESE
        KOREAN -> Locale.KOREAN
        ITALIAN -> Locale.ITALIAN
        TURKISH -> Locale("tr")
        else -> null
    }
}
