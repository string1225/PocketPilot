package com.string1225.pocketpilot.ui

import com.string1225.pocketpilot.model.AppLanguage

internal fun ppText(language: AppLanguage, chinese: String, english: String): String =
    if (language == AppLanguage.ENGLISH) english else chinese
