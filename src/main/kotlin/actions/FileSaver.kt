package no.huzef.blackhole.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

object FileSaver {

    data class SaveResult(val success: Boolean, val message: String)

    /**
     * Saves generated files to appropriate source folders based on their package declaration.
     * Files with "package db;" go to src/db, "package dto;" goes to src/dto, etc.
     */
    fun saveToProject(project: Project, files: Map<String, String>): SaveResult {
        if (files.isEmpty()) {
            return SaveResult(false, "Nothing to save. Convert SQL first.")
        }

        val basePath = project.basePath
            ?: return SaveResult(false, "Could not determine project path.")

        val savedDirs = mutableSetOf<File>()
        var count = 0

        for ((fileName, content) in files) {
            val packageName = extractPackage(content) ?: "dto"
            val targetDir = File(basePath, "src/$packageName")
            targetDir.mkdirs()
            File(targetDir, fileName).writeText(content)
            savedDirs.add(targetDir)
            count++
        }

        for (dir in savedDirs) refreshFileSystem(dir)

        val paths = savedDirs.joinToString("\n") { it.absolutePath }
        return SaveResult(true, "Saved $count file(s) to:\n$paths")
    }

    private fun extractPackage(content: String): String? {
        val match = Regex("""package\s+([\w.]+)\s*;""").find(content) ?: return null
        return match.groupValues[1]
    }

    private fun refreshFileSystem(dir: File) {
        LocalFileSystem.getInstance()
            .refreshAndFindFileByIoFile(dir)
            ?.refresh(true, true)
    }
}