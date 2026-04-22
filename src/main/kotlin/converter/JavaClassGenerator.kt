package no.huzef.blackhole.converter

import no.huzef.blackhole.converter.TypeMapper.capitalizeFirst
import no.huzef.blackhole.converter.TypeMapper.toJavaField
import no.huzef.blackhole.converter.TypeMapper.toPascalCase

object JavaClassGenerator {

    private const val PACKAGE_NAME = "dto"
    private const val RECORD_FIELD_THRESHOLD = 6

    /**
     * Generates files with inheritance: one abstract parent + child classes + standalone records.
     */
    fun generateFilesWithInheritance(
        parentName: String,
        sharedColumns: List<Column>,
        childTables: List<Table>,
        standaloneTables: List<Table>
    ): Map<String, String> {
        val files = mutableMapOf<String, String>()
        val sharedNames = sharedColumns.map { it.name.uppercase() }.toSet()

        files["$parentName.java"] = buildFileContent(
            generateAbstractClass(parentName, sharedColumns),
            sharedColumns
        )

        for (child in childTables) {
            val className = child.name.toPascalCase()
            files["$className.java"] = buildFileContent(
                generateChildClass(child, parentName, sharedColumns, sharedNames),
                child.columns
            )
        }

        for (table in standaloneTables) {
            val className = table.name.toPascalCase()
            files["$className.java"] = buildFileContent(
                generateStandaloneClass(table),
                table.columns
            )
        }

        return files
    }

    /**
     * Generates standalone files for each table with no inheritance.
     */
    fun generateAllFiles(tables: List<Table>): Map<String, String> {
        val files = mutableMapOf<String, String>()
        for (table in tables) {
            val className = table.name.toPascalCase()
            files["$className.java"] = buildFileContent(
                generateStandaloneClass(table),
                table.columns
            )
        }
        return files
    }

    // --- File header (package + imports) ---

    private fun buildFileContent(classCode: String, columns: List<Column>): String {
        val sb = StringBuilder()
        sb.appendLine("package $PACKAGE_NAME;")
        sb.appendLine()

        if (TypeMapper.requiresLocalDateImport(columns)) {
            sb.appendLine("import java.time.LocalDate;")
            sb.appendLine()
        }

        sb.append(classCode)
        return sb.toString()
    }

    // --- Class generation ---

    private fun generateAbstractClass(className: String, columns: List<Column>): String {
        val fields = columns.map { it.toJavaField() }
        return buildString {
            appendLine("public abstract class $className {")
            appendFields(fields)
            appendLine()
            appendConstructor(className, fields)
            appendLine()
            appendGetters(fields)
            appendToString(className, fields, extendsParent = false)
            appendLine("}")
        }
    }

    private fun generateChildClass(
        table: Table,
        parentName: String,
        sharedColumns: List<Column>,
        sharedNames: Set<String>
    ): String {
        val className = table.name.toPascalCase()
        val extraFields = table.columns
            .filter { it.name.uppercase() !in sharedNames }
            .map { it.toJavaField() }
        val parentFields = sharedColumns.map { it.toJavaField() }

        return buildString {
            appendLine("public class $className extends $parentName {")
            appendFields(extraFields)
            appendLine()
            appendChildConstructor(className, parentFields, extraFields)
            appendLine()
            appendGetters(extraFields)
            appendToString(className, extraFields, extendsParent = true)
            appendLine("}")
        }
    }

    private fun generateStandaloneClass(table: Table): String {
        val className = table.name.toPascalCase()
        val fields = table.columns.map { it.toJavaField() }

        if (fields.size <= RECORD_FIELD_THRESHOLD) {
            return generateRecord(className, fields)
        }

        return buildString {
            appendLine("public class $className {")
            appendFields(fields)
            appendLine()
            appendConstructor(className, fields)
            appendLine()
            appendCompactGetters(fields)
            appendLine("}")
        }
    }

    private fun generateRecord(className: String, fields: List<Pair<String, String>>): String {
        val params = fields.joinToString(",\n                        ") { "${it.first} ${it.second}" }
        return "public record $className($params) {\n}"
    }

    // --- Reusable building blocks ---

    private fun StringBuilder.appendFields(fields: List<Pair<String, String>>) {
        for ((type, name) in fields) appendLine("    private final $type $name;")
    }

    private fun StringBuilder.appendConstructor(className: String, fields: List<Pair<String, String>>) {
        val params = fields.joinToString(", ") { "${it.first} ${it.second}" }
        appendLine("    public $className($params) {")
        for ((_, name) in fields) appendLine("        this.$name = $name;")
        appendLine("    }")
    }

    private fun StringBuilder.appendChildConstructor(
        className: String,
        parentFields: List<Pair<String, String>>,
        extraFields: List<Pair<String, String>>
    ) {
        val allParams = (parentFields + extraFields).joinToString(", ") { "${it.first} ${it.second}" }
        appendLine("    public $className($allParams) {")
        appendLine("        super(${parentFields.joinToString(", ") { it.second }});")
        for ((_, name) in extraFields) appendLine("        this.$name = $name;")
        appendLine("    }")
    }

    private fun StringBuilder.appendGetters(fields: List<Pair<String, String>>) {
        for ((type, name) in fields) {
            val getter = getterName(type, name)
            appendLine("    public $type $getter() {")
            appendLine("        return $name;")
            appendLine("    }")
            appendLine()
        }
    }

    private fun StringBuilder.appendCompactGetters(fields: List<Pair<String, String>>) {
        for ((type, name) in fields) {
            val getter = getterName(type, name)
            appendLine("    public $type $getter() { return $name; }")
        }
    }

    private fun StringBuilder.appendToString(
        className: String,
        fields: List<Pair<String, String>>,
        extendsParent: Boolean
    ) {
        appendLine("    @Override")
        appendLine("    public String toString() {")
        val prefix = if (extendsParent) "super.toString() + " else ""
        appendLine("        return $prefix\"$className{\" +")
        for ((i, f) in fields.withIndex()) {
            val comma = if (i == 0) "" else ", "
            if (f.first == "String") {
                appendLine("                \"${comma}${f.second}='\" + ${f.second} + '\\'' +")
            } else {
                appendLine("                \"${comma}${f.second}=\" + ${f.second} +")
            }
        }
        appendLine("                '}';")
        appendLine("    }")
    }

    private fun getterName(type: String, name: String): String {
        return if (type == "boolean") "is${name.capitalizeFirst()}" else "get${name.capitalizeFirst()}"
    }
}