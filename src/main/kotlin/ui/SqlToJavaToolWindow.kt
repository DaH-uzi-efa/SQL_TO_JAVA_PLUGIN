package no.huzef.blackhole.ui

import com.intellij.openapi.project.Project
import no.huzef.blackhole.actions.FileSaver
import no.huzef.blackhole.converter.Column
import no.huzef.blackhole.converter.DbServiceGenerator
import no.huzef.blackhole.converter.JavaClassGenerator
import no.huzef.blackhole.converter.SharedColumnDetector
import no.huzef.blackhole.converter.SqlParser
import no.huzef.blackhole.converter.Table
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.*

class SqlToJavaToolWindow(private val project: Project) {

    private val sqlInput = createSqlInput()
    private val javaOutput = createJavaOutput()
    private var generatedFiles: Map<String, String> = emptyMap()

    val panel: JPanel = buildPanel()

    // --- Panel construction ---

    private fun buildPanel(): JPanel {
        val root = JPanel(BorderLayout(0, 8))
        root.add(JLabel("  SQL Input:"), BorderLayout.NORTH)
        root.add(buildSplitPane(), BorderLayout.CENTER)
        root.add(buildButtonPanel(), BorderLayout.SOUTH)
        return root
    }

    private fun buildSplitPane(): JSplitPane {
        return JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            JScrollPane(sqlInput),
            JScrollPane(javaOutput)
        ).apply { resizeWeight = 0.5 }
    }

    private fun buildButtonPanel(): JPanel {
        return JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JButton("Convert to Java").apply { addActionListener { onConvert() } })
            add(JButton("Generate DB Service").apply { addActionListener { onGenerateDbService() } })
            add(JButton("Save to Project").apply { addActionListener { onSave() } })
            add(JButton("Copy Output").apply { addActionListener { onCopy() } })
        }
    }

    private fun createSqlInput() = JTextArea(12, 50).apply {
        lineWrap = true
        wrapStyleWord = true
        text = "-- Paste your CREATE TABLE statements here"
    }

    private fun createJavaOutput() = JTextArea(12, 50).apply {
        lineWrap = true
        wrapStyleWord = true
        isEditable = false
    }

    // --- Button actions ---

    private fun onConvert() {
        val tables = SqlParser.parseTables(sqlInput.text)

        if (tables.isEmpty()) {
            javaOutput.text = "// Could not parse any CREATE TABLE statements."
            generatedFiles = emptyMap()
            return
        }

        val sharedColumns = SharedColumnDetector.findSharedColumns(tables)

        if (sharedColumns.size >= 3 && tryGenerateWithInheritance(tables, sharedColumns)) {
            return
        }

        generatedFiles = JavaClassGenerator.generateAllFiles(tables)
        displayGeneratedFiles()
    }

    private fun onGenerateDbService() {
        val tables = SqlParser.parseTables(sqlInput.text)
        if (tables.isEmpty()) {
            JOptionPane.showMessageDialog(panel, "No tables found. Paste CREATE TABLE statements first.")
            return
        }

        val options = askUserForCrudOptions() ?: return
        if (!options.insert && !options.select && !options.update && !options.delete) {
            JOptionPane.showMessageDialog(panel, "Select at least one CRUD operation.")
            return
        }

        val serviceName = askUserForServiceName() ?: return

        // Detect shared columns so DB Service knows how to order child constructor args
        val sharedColumns = SharedColumnDetector.findSharedColumns(tables)
        val sharedColumnNames = sharedColumns.map { it.name.uppercase() }.toSet()

        val code = DbServiceGenerator.generate(serviceName, tables, options, sharedColumnNames)
        generatedFiles = mapOf("$serviceName.java" to code)
        displayGeneratedFiles()
    }

    private fun tryGenerateWithInheritance(
        tables: List<Table>,
        sharedColumns: List<Column>
    ): Boolean {
        val (childTables, standaloneTables) =
            SharedColumnDetector.findChildAndStandalone(tables, sharedColumns)

        if (childTables.size < 2) return false
        if (!askUserWantsSuperclass(childTables, sharedColumns)) return false

        val parentName = askUserForSuperclassName() ?: return false

        generatedFiles = JavaClassGenerator.generateFilesWithInheritance(
            parentName, sharedColumns, childTables, standaloneTables
        )
        displayGeneratedFiles()
        return true
    }

    private fun onSave() {
        val result = FileSaver.saveToProject(project, generatedFiles)
        val type = if (result.success) JOptionPane.INFORMATION_MESSAGE else JOptionPane.WARNING_MESSAGE
        val title = if (result.success) "Files Saved" else "Cannot Save"
        JOptionPane.showMessageDialog(panel, result.message, title, type)
    }

    private fun onCopy() {
        javaOutput.selectAll()
        javaOutput.copy()
        javaOutput.select(0, 0)
    }

    // --- User prompts ---

    private fun askUserWantsSuperclass(
        childTables: List<Table>,
        sharedColumns: List<Column>
    ): Boolean {
        val tableNames = childTables.joinToString(", ") { it.name }
        val colNames = sharedColumns.joinToString(", ") { it.name }

        val choice = JOptionPane.showConfirmDialog(
            panel,
            "Tables [$tableNames] share these columns:\n$colNames\n\nGenerate an abstract superclass?",
            "Shared Columns Detected",
            JOptionPane.YES_NO_OPTION
        )
        return choice == JOptionPane.YES_OPTION
    }

    private fun askUserForSuperclassName(): String? {
        val input = JOptionPane.showInputDialog(
            panel,
            "Enter the superclass name:",
            "Superclass Name",
            JOptionPane.QUESTION_MESSAGE
        )
        return input?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun askUserForServiceName(): String? {
        val input = JOptionPane.showInputDialog(
            panel,
            "Enter DB service class name:",
            "Service Name",
            JOptionPane.QUESTION_MESSAGE
        )
        return input?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun askUserForCrudOptions(): DbServiceGenerator.CrudOptions? {
        val insertBox = JCheckBox("INSERT (add methods)", true)
        val selectBox = JCheckBox("SELECT (get methods)", true)
        val updateBox = JCheckBox("UPDATE (update methods)", false)
        val deleteBox = JCheckBox("DELETE (delete methods)", false)

        val checkPanel = JPanel(GridLayout(4, 1)).apply {
            add(insertBox)
            add(selectBox)
            add(updateBox)
            add(deleteBox)
        }

        val result = JOptionPane.showConfirmDialog(
            panel,
            checkPanel,
            "Select CRUD Operations",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE
        )

        if (result != JOptionPane.OK_OPTION) return null

        return DbServiceGenerator.CrudOptions(
            insert = insertBox.isSelected,
            select = selectBox.isSelected,
            update = updateBox.isSelected,
            delete = deleteBox.isSelected
        )
    }

    // --- Output rendering ---

    private fun displayGeneratedFiles() {
        javaOutput.text = generatedFiles.entries.joinToString("\n\n") { (name, code) ->
            "// === $name ===\n$code"
        }
    }
}