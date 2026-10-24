package com.sherwood.wta.core.agent.prompt

import com.sherwood.wta.core.i18n.AppLanguage

enum class PromptLang { EN, ZH }

fun AppLanguage.toPromptLang(): PromptLang = when (this) {
    AppLanguage.CHINESE -> PromptLang.ZH
    else -> PromptLang.EN
}
