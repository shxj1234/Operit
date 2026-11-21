package com.ai.assistance.operit.core.tools.skill

import java.io.File

data class SkillPackage(
    val name: String,
    val description: String,
    val directory: File,
    val skillFile: File,
    // 内置 Skill（随 APK 解压到应用私有目录），不可删除
    val isBuiltIn: Boolean = false
)
