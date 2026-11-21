package com.ai.assistance.operit.core.tools.skill

import android.content.Context
import android.os.Environment
import com.ai.assistance.operit.R
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class SkillManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SkillManager"

        // 内置 Skill：随 APK 打包的 zip，首次扫描时解压到应用私有目录后按现有机制使用。
        // 资产不是文件系统上的真实文件，终端工具按路径读不到，所以必须解压一次，不能直接读 assets。
        private const val BUILTIN_SKILL_ASSET = "skills/ningning-fusion-armor.zip"
        private const val BUILTIN_SKILL_DIR_NAME = "ningning-fusion-armor"
        // 内置 Skill 版本标记：换新版 zip 时同步改这个值，下次扫描会删旧目录重新解压。
        private const val BUILTIN_SKILL_VERSION = "1"
        private const val BUILTIN_SKILLS_DIR_NAME = "builtin_skills"

        @Volatile private var INSTANCE: SkillManager? = null

        fun getInstance(context: Context): SkillManager {
            return INSTANCE
                ?: synchronized(this) {
                    INSTANCE ?: SkillManager(context.applicationContext).also { INSTANCE = it }
                }
        }
    }

    private val availableSkills = mutableMapOf<String, SkillPackage>()
    private val skillLoadErrors = mutableMapOf<String, String>()
    private val builtinExtractLock = Any()

    private fun getSkillsRootDir(): File {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val operitDir = File(downloadsDir, "Operit")
        val skillsDir = File(operitDir, "skills")
        if (!skillsDir.exists()) {
            skillsDir.mkdirs()
        }
        return skillsDir
    }

    fun getSkillsDirectoryPath(): String {
        return getSkillsRootDir().absolutePath
    }

    // 内置 Skill 解压目标：应用私有目录，不走 Download 公共目录，用户删不掉也改不了。
    private fun getBuiltinSkillsRootDir(): File {
        val builtinDir = File(context.filesDir, BUILTIN_SKILLS_DIR_NAME)
        if (!builtinDir.exists()) {
            builtinDir.mkdirs()
        }
        return builtinDir
    }

    // 确保内置 Skill 已解压：目录已存在且 SKILL.md 在、版本标记一致就跳过，否则重新解压。
    // 解压失败只记一条加载错误，不抛出去——Download 目录的扫描照常进行。
    private fun ensureBuiltinSkillExtracted() {
        synchronized(builtinExtractLock) {
            val builtinRoot = getBuiltinSkillsRootDir()
            val skillDir = File(builtinRoot, BUILTIN_SKILL_DIR_NAME)
            val versionFile = File(builtinRoot, ".${BUILTIN_SKILL_DIR_NAME}.version")
            val installedVersion = try {
                versionFile.takeIf { it.isFile }?.readText()?.trim()
            } catch (_: Exception) {
                null
            }
            val existingSkillFile = File(skillDir, "SKILL.md").let { primary ->
                if (primary.exists()) primary else File(skillDir, "skill.md")
            }
            if (existingSkillFile.isFile && installedVersion == BUILTIN_SKILL_VERSION) {
                return
            }

            // 先解到临时目录、校验通过后再替换旧目录：中途失败旧版本还在。
            val tmpZip = File(context.cacheDir, "builtin_skill_${BUILTIN_SKILL_DIR_NAME}.zip")
            val tmpDir = File(builtinRoot, ".${BUILTIN_SKILL_DIR_NAME}.tmp")
            try {
                tmpDir.deleteRecursively()
                tmpDir.mkdirs()
                context.assets.open(BUILTIN_SKILL_ASSET).use { input ->
                    FileOutputStream(tmpZip).use { output -> input.copyTo(output) }
                }
                unzipToDirectory(tmpZip, tmpDir)
                val extractedSkillDir = File(tmpDir, BUILTIN_SKILL_DIR_NAME)
                val extractedSkillFile = File(extractedSkillDir, "SKILL.md").let { primary ->
                    if (primary.exists()) primary else File(extractedSkillDir, "skill.md")
                }
                if (!extractedSkillFile.isFile) {
                    throw IllegalStateException("Builtin skill zip has no SKILL.md: $BUILTIN_SKILL_ASSET")
                }
                skillDir.deleteRecursively()
                if (!extractedSkillDir.renameTo(skillDir)) {
                    throw IllegalStateException("Failed to install builtin skill: $BUILTIN_SKILL_DIR_NAME")
                }
                // 版本标记最后写：没写上说明这次解压没完成，下次扫描会重新解压。
                versionFile.writeText(BUILTIN_SKILL_VERSION)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to extract builtin skill", e)
                skillLoadErrors[BUILTIN_SKILL_DIR_NAME] = context.getString(
                    R.string.skill_error_scan_failed,
                    e.message ?: e.javaClass.simpleName
                )
            } finally {
                try {
                    tmpDir.deleteRecursively()
                } catch (_: Exception) {
                }
                try {
                    if (tmpZip.exists()) tmpZip.delete()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun refreshAvailableSkills() {
        availableSkills.clear()
        skillLoadErrors.clear()

        ensureBuiltinSkillExtracted()
        // 内置目录先扫：同名时 Download 目录的副本走现有的重名报错，内置优先。
        scanSkillsDirectory(getBuiltinSkillsRootDir(), isBuiltIn = true)

        val skillsDir = try {
            getSkillsRootDir()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error getting skills directory", e)
            skillLoadErrors[context.getString(R.string.skills)] =
                context.getString(R.string.skill_error_cannot_access_dir, e.message ?: "")
            return
        }

        scanSkillsDirectory(skillsDir, isBuiltIn = false)
    }

    private fun scanSkillsDirectory(skillsDir: File, isBuiltIn: Boolean) {
        if (!skillsDir.exists() || !skillsDir.isDirectory) {
            return
        }

        val children = skillsDir.listFiles() ?: emptyArray()
        for (child in children) {
            if (!child.isDirectory) continue

            val skillFile = File(child, "SKILL.md").let { primary ->
                if (primary.exists()) primary else File(child, "skill.md")
            }

            if (!skillFile.exists() || !skillFile.isFile) {
                skillLoadErrors[child.name] = context.getString(
                    R.string.skill_error_missing_skill_md,
                    child.absolutePath
                )
                continue
            }

            try {
                val (name, description) = parseSkillMetadata(skillFile)
                val skillName = name.ifBlank { child.name }
                val skillDesc = description.ifBlank { "" }

                if (availableSkills.containsKey(skillName)) {
                    val existingDirName = availableSkills[skillName]?.directory?.name ?: skillName
                    skillLoadErrors[child.name] = context.getString(
                        R.string.skill_error_duplicate_scanned_name,
                        skillName,
                        existingDirName
                    )
                    continue
                }

                availableSkills[skillName] = SkillPackage(
                    name = skillName,
                    description = skillDesc,
                    directory = child,
                    skillFile = skillFile,
                    isBuiltIn = isBuiltIn
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error loading skill from ${skillFile.absolutePath}", e)
                skillLoadErrors[child.name] = context.getString(
                    R.string.skill_error_scan_failed,
                    e.message ?: e.javaClass.simpleName
                )
            }
        }
    }

    private fun parseSkillMetadata(skillFile: File): Pair<String, String> {
        val lines = skillFile.bufferedReader().use { it.readLines() }

        var name = ""
        var description = ""

        if (lines.isNotEmpty() && lines[0].trim() == "---") {
            val endIndex = lines.drop(1).indexOfFirst { it.trim() == "---" }
            if (endIndex >= 0) {
                val frontmatter = lines.subList(1, endIndex + 1)
                frontmatter.forEach { lineRaw ->
                    val line = lineRaw.trim()
                    val idx = line.indexOf(':')
                    if (idx <= 0) return@forEach
                    val key = line.substring(0, idx).trim()
                    val value = unquote(line.substring(idx + 1).trim())
                    when (key.lowercase()) {
                        "name" -> if (name.isBlank()) name = value
                        "description" -> if (description.isBlank()) description = value
                    }
                }
            }
        }

        if (name.isBlank() || description.isBlank()) {
            lines.take(40).forEach { lineRaw ->
                val line = lineRaw.trim()
                val idx = line.indexOf(':')
                if (idx <= 0) return@forEach
                val key = line.substring(0, idx).trim()
                val value = unquote(line.substring(idx + 1).trim())
                when (key.lowercase()) {
                    "name" -> if (name.isBlank()) name = value
                    "description" -> if (description.isBlank()) description = value
                }
            }
        }

        return Pair(name, description)
    }

    private fun unquote(valueRaw: String): String {
        var value = valueRaw
        if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith('\'') && value.endsWith('\''))) {
            if (value.length >= 2) value = value.substring(1, value.length - 1)
        }
        return value
    }

    fun getAvailableSkills(): Map<String, SkillPackage> {
        refreshAvailableSkills()
        return availableSkills.toMap()
    }

    fun getAvailableSkillsSnapshot(): Pair<Map<String, SkillPackage>, Map<String, String>> {
        refreshAvailableSkills()
        return availableSkills.toMap() to skillLoadErrors.toMap()
    }

    fun getSkillLoadErrors(): Map<String, String> {
        refreshAvailableSkills()
        return skillLoadErrors.toMap()
    }

    fun readSkillContent(skillName: String): String? {
        refreshAvailableSkills()
        val skill = availableSkills[skillName] ?: return null
        return try {
            skill.skillFile.readText()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to read SKILL.md for $skillName", e)
            null
        }
    }

    fun deleteSkill(skillName: String): Boolean {
        refreshAvailableSkills()
        val skill = availableSkills[skillName] ?: return false
        if (skill.isBuiltIn) {
            AppLogger.w(TAG, "Refusing to delete builtin skill: $skillName")
            return false
        }
        return try {
            val ok = skill.directory.deleteRecursively()
            if (ok) {
                availableSkills.remove(skillName)
            }
            ok
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to delete skill $skillName", e)
            false
        }
    }

    fun getSkillSystemPrompt(skillName: String): String? {
        refreshAvailableSkills()
        val skill = availableSkills[skillName] ?: return null
        val content = try {
            skill.skillFile.readText()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to read skill content: ${skill.skillFile.absolutePath}", e)
            ""
        }

        val sb = StringBuilder()
        sb.appendLine("Using package (Skill): ${skill.name}")
        sb.appendLine("Use Time: ${java.time.LocalDateTime.now()}")
        sb.appendLine("Execution policy:")
        sb.appendLine("Prioritize using the skill-provided instructions and bundled scripts, and complete tasks with terminal-related tools.")
        if (skill.description.isNotBlank()) {
            sb.appendLine("Description: ${skill.description}")
        }
        sb.appendLine("SKILL.md path: ${skill.skillFile.absolutePath}")
        sb.appendLine("Skill directory: ${skill.directory.absolutePath}")
        sb.appendLine("Directory structure:")
        sb.appendLine(buildDirectoryTreeText(skill.directory))
        sb.appendLine()
        sb.appendLine("SKILL.md:")
        sb.appendLine(content)

        return sb.toString()
    }

    private fun buildDirectoryTreeText(rootDir: File): String {
        val sb = StringBuilder()

        fun walk(dir: File, indent: String) {
            val children = dir.listFiles()
                ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                ?: emptyList()

            for (child in children) {
                sb.append(indent)
                sb.append("- ")
                sb.append(child.name)
                if (child.isDirectory) {
                    sb.appendLine("/")
                    walk(child, indent + "  ")
                } else {
                    sb.appendLine()
                }
            }
        }

        walk(rootDir, indent = "")

        if (sb.length == 0) return "(empty directory)"
        return sb.toString().trimEnd()
    }

    fun importSkillFromZip(zipFile: File): String {
        return importSkillFromZip(zipFile, null)
    }

    data class SkillImportResult(
        val message: String,
        val installedDir: File?
    )

    fun importSkillFromZip(zipFile: File, subDirPathInZip: String?): String {
        return importSkillFromZipDetailed(zipFile, subDirPathInZip).message
    }

    fun importSkillFromZipDetailed(zipFile: File, subDirPathInZip: String?): SkillImportResult {
        if (!zipFile.exists() || !zipFile.canRead()) {
            return SkillImportResult(context.getString(R.string.skill_error_cannot_read_file, zipFile.absolutePath), null)
        }
        if (!zipFile.name.endsWith(".zip", ignoreCase = true)) {
            return SkillImportResult(context.getString(R.string.skill_error_only_support_zip), null)
        }

        val skillsRoot = try {
            getSkillsRootDir()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error getting skills directory", e)
            return SkillImportResult(context.getString(R.string.skill_error_cannot_access_dir, e.message ?: ""), null)
        }

        val tmpDir = File(skillsRoot, ".import_tmp_${System.currentTimeMillis()}")
        if (!tmpDir.mkdirs()) {
            return SkillImportResult(context.getString(R.string.skill_error_create_tmp_dir_failed, tmpDir.absolutePath), null)
        }

        fun cleanupTmp() {
            try {
                tmpDir.deleteRecursively()
            } catch (_: Exception) {
            }
        }

        try {
            unzipToDirectory(zipFile, tmpDir)

            val normalizedSubDir = subDirPathInZip
                ?.trim()
                ?.trimStart('/')
                ?.trimEnd('/')
                ?.takeIf { it.isNotBlank() }

            val zipRootDir = tmpDir
                .listFiles()
                ?.filter { it.isDirectory }
                ?.singleOrNull()
                ?: tmpDir

            val searchRoot: File = if (normalizedSubDir == null) {
                tmpDir
            } else {
                val baseCanonical = zipRootDir.canonicalFile
                val resolved = File(zipRootDir, normalizedSubDir)
                val resolvedCanonical = resolved.canonicalFile
                if (!resolvedCanonical.path.startsWith(baseCanonical.path + File.separator)) {
                    cleanupTmp()
                    return SkillImportResult(context.getString(R.string.skill_error_import_invalid_path), null)
                }
                if (!resolvedCanonical.exists()) {
                    cleanupTmp()
                    return SkillImportResult(context.getString(R.string.skill_error_import_path_not_found, normalizedSubDir), null)
                }
                resolvedCanonical
            }

            val directSkillFile = if (searchRoot.isDirectory) {
                File(searchRoot, "SKILL.md").let { primary ->
                    if (primary.exists()) primary else File(searchRoot, "skill.md")
                }.takeIf { it.exists() && it.isFile }
            } else {
                null
            }

            val skillMdCandidates = if (directSkillFile != null) {
                listOf(directSkillFile)
            } else {
                searchRoot.walkTopDown()
                    .filter { it.isFile && (it.name.equals("SKILL.md", ignoreCase = true) || it.name.equals("skill.md", ignoreCase = true)) }
                    .take(10)
                    .toList()
            }

            if (skillMdCandidates.isEmpty()) {
                cleanupTmp()
                return SkillImportResult(if (normalizedSubDir == null) {
                    context.getString(R.string.skill_error_import_no_skill_md)
                } else {
                    context.getString(R.string.skill_error_import_no_skill_md_in_path)
                }, null)
            }

            val selectedSkillFile = skillMdCandidates.first()
            val selectedSkillDir = selectedSkillFile.parentFile ?: run {
                cleanupTmp()
                return SkillImportResult(context.getString(R.string.skill_error_import_skill_md_path_invalid), null)
            }

            val (metaName, metaDesc) = parseSkillMetadata(selectedSkillFile)
            val baseName = metaName.ifBlank {
                val isTmpRoot = try {
                    selectedSkillDir.canonicalFile == tmpDir.canonicalFile
                } catch (_: Exception) {
                    selectedSkillDir.absolutePath == tmpDir.absolutePath
                }
                if (isTmpRoot) {
                    zipFile.nameWithoutExtension
                } else {
                    selectedSkillDir.name.ifBlank { zipFile.nameWithoutExtension }
                }
            }
            val finalDir = File(skillsRoot, baseName.trim().ifBlank { "skill" })

            if (finalDir.exists()) {
                cleanupTmp()
                return SkillImportResult(context.getString(R.string.skill_error_import_duplicate_name, finalDir.name), null)
            }

            // 同名检查也要覆盖内置 Skill：内置优先，手动导入同名时直接报重名，不静默覆盖任何一方。
            refreshAvailableSkills()
            if (availableSkills.containsKey(finalDir.name)) {
                cleanupTmp()
                return SkillImportResult(context.getString(R.string.skill_error_import_duplicate_name, finalDir.name), null)
            }

            // Copy the detected skill directory to final location
            selectedSkillDir.copyRecursively(finalDir, overwrite = false)
            cleanupTmp()

            // refresh cache
            refreshAvailableSkills()

            val desc = metaDesc.ifBlank { "" }
            return SkillImportResult(if (desc.isNotBlank()) {
                context.getString(R.string.skill_imported_with_desc, finalDir.name, desc)
            } else {
                context.getString(R.string.skill_imported, finalDir.name)
            }, finalDir)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to import skill from zip", e)
            cleanupTmp()
            return SkillImportResult(context.getString(R.string.skill_error_import_failed, e.message ?: ""), null)
        }
    }


    private fun unzipToDirectory(zipFile: File, destinationDir: File) {
        val destCanonical = destinationDir.canonicalFile
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val entry = zis.nextEntry ?: break

                val outFile = File(destinationDir, entry.name)
                val outCanonical = outFile.canonicalFile
                if (!outCanonical.path.startsWith(destCanonical.path + File.separator)) {
                    zis.closeEntry()
                    throw IllegalArgumentException("Zip entry is outside target dir: ${entry.name}")
                }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                    zis.closeEntry()
                    continue
                }

                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { fos ->
                    while (true) {
                        val read = zis.read(buffer)
                        if (read <= 0) break
                        fos.write(buffer, 0, read)
                    }
                }
                zis.closeEntry()
            }
        }
    }
}
