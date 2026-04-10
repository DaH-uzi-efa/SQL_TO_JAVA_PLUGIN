package no.huzef.blackhole

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.openapi.vfs.LocalFileSystem
import javax.swing.*
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.File

class MyToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JPanel(BorderLayout(0, 8))

        val sqlInput = JTextArea(12, 50).apply {
            lineWrap = true
            wrapStyleWord = true
            text = "-- Paste your CREATE TABLE statements here"
        }

        val javaOutput = JTextArea(12, 50).apply {
            lineWrap = true
            wrapStyleWord = true
            isEditable = false
        }

        var generatedFiles: Map<String, String> = emptyMap()

        val convertBtn = JButton("Convert to Java")

        convertBtn.addActionListener {
            val sql = sqlInput.text
            val tables = SqlToJavaConverter.parseTables(sql)

            if (tables.isEmpty()) {
                javaOutput.text = "// Could not parse any CREATE TABLE statements."
                generatedFiles = emptyMap()
                return@addActionListener
            }

            val sharedColumns = SqlToJavaConverter.findSharedColumns(tables)

            if (sharedColumns.size >= 3) {
                val sharedNames = sharedColumns.map { it.name.uppercase() }.toSet()
                val childTables = tables.filter { t ->
                    val cols = t.columns.map { it.name.uppercase() }.toSet()
                    cols.containsAll(sharedNames) && t.columns.size > sharedNames.size
                }
                val standaloneTables = tables - childTables.toSet()

                if (childTables.size >= 2) {
                    val tableNames = childTables.joinToString(", ") { it.name }
                    val colNames = sharedColumns.joinToString(", ") { it.name }

                    val choice = JOptionPane.showConfirmDialog(
                        panel,
                        "Tables [$tableNames] share these columns:\n$colNames\n\nGenerate an abstract superclass?",
                        "Shared Columns Detected",
                        JOptionPane.YES_NO_OPTION
                    )

                    if (choice == JOptionPane.YES_OPTION) {
                        val parentName = JOptionPane.showInputDialog(
                            panel,
                            "Enter the superclass name:",
                            "Superclass Name",
                            JOptionPane.QUESTION_MESSAGE
                        )

                        if (!parentName.isNullOrBlank()) {
                            generatedFiles = SqlToJavaConverter.generateFilesWithInheritance(
                                parentName.trim(), sharedColumns, childTables, standaloneTables
                            )
                            javaOutput.text = generatedFiles.entries.joinToString("\n\n") { (name, code) ->
                                "// === $name ===\n$code"
                            }
                            return@addActionListener
                        }
                    }
                }
            }

            generatedFiles = SqlToJavaConverter.generateAllFiles(tables)
            javaOutput.text = generatedFiles.entries.joinToString("\n\n") { (name, code) ->
                "// === $name ===\n$code"
            }
        }

        val saveBtn = JButton("Save to Project").apply {
            addActionListener {
                if (generatedFiles.isEmpty()) {
                    JOptionPane.showMessageDialog(panel, "Nothing to save. Convert SQL first.")
                    return@addActionListener
                }

                val basePath = project.basePath
                if (basePath == null) {
                    JOptionPane.showMessageDialog(panel, "Could not determine project path.")
                    return@addActionListener
                }

                val dtoDir = File(basePath, "src/dto")
                dtoDir.mkdirs()

                for ((fileName, content) in generatedFiles) {
                    File(dtoDir, fileName).writeText(content)
                }

                LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dtoDir)?.refresh(true, true)

                JOptionPane.showMessageDialog(
                    panel,
                    "Saved ${generatedFiles.size} files to:\n${dtoDir.absolutePath}",
                    "Files Saved",
                    JOptionPane.INFORMATION_MESSAGE
                )
            }
        }

        val copyBtn = JButton("Copy Output").apply {
            addActionListener {
                javaOutput.selectAll()
                javaOutput.copy()
                javaOutput.select(0, 0)
            }
        }

        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        buttonPanel.add(convertBtn)
        buttonPanel.add(saveBtn)
        buttonPanel.add(copyBtn)

        panel.add(JLabel("  SQL Input:"), BorderLayout.NORTH)
        panel.add(JSplitPane(JSplitPane.VERTICAL_SPLIT,
            JScrollPane(sqlInput),
            JScrollPane(javaOutput)
        ).apply { resizeWeight = 0.5 }, BorderLayout.CENTER)
        panel.add(buttonPanel, BorderLayout.SOUTH)

        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }
}