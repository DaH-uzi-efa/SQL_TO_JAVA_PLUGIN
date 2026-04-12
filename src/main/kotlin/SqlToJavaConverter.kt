package no.huzef.blackhole

object SqlToJavaConverter {

    data class Column(val name: String, val sqlType: String, val isNotNull: Boolean, val isPrimaryKey: Boolean)
    data class Table(val name: String, val columns: List<Column>, val foreignKeys: Map<String, String>)

    private const val WORD = "[\\p{L}\\p{N}_]+"

    fun parseTables(sql: String): List<Table> {
        val regex = Regex(
            """CREATE\s+TABLE\s+`?($WORD)`?\s*\((.*?)\);""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        return regex.findAll(sql).map { match ->
            val tableName = match.groupValues[1]
            val body = match.groupValues[2]
            val columns = mutableListOf<Column>()
            val foreignKeys = mutableMapOf<String, String>()

            for (line in body.split(",").map { it.trim() }.filter { it.isNotBlank() }) {
                val upper = line.uppercase()
                when {
                    upper.startsWith("PRIMARY KEY") -> {}
                    upper.startsWith("FOREIGN KEY") -> {
                        val fk = Regex(
                            """FOREIGN\s+KEY\s*\(($WORD)\)\s*REFERENCES\s+($WORD)""",
                            RegexOption.IGNORE_CASE
                        ).find(line)
                        if (fk != null) foreignKeys[fk.groupValues[1]] = fk.groupValues[2]
                    }
                    upper.startsWith("CONSTRAINT") || upper.startsWith("UNIQUE") ||
                            upper.startsWith("INDEX") || upper.startsWith("KEY") -> {}
                    else -> {
                        val parts = line.split("\\s+".toRegex())
                        if (parts.size >= 2) {
                            val colName = parts[0].replace("`", "")
                            val sqlType = parts[1].uppercase().replace(Regex("\\(.*\\)"), "")
                            val isPk = upper.contains("PRIMARY KEY")
                            val isNotNull = upper.contains("NOT NULL") || isPk
                            columns.add(Column(colName, sqlType, isNotNull, isPk))
                        }
                    }
                }
            }
            Table(tableName, columns, foreignKeys)
        }.toList()
    }

    fun findSharedColumns(tables: List<Table>): List<Column> {
        if (tables.size < 2) return emptyList()

        // For each column, track which tables have it
        val colToTables = mutableMapOf<String, MutableSet<String>>()
        for (t in tables) for (c in t.columns) {
            colToTables.getOrPut(c.name.uppercase()) { mutableSetOf() }.add(t.name)
        }

        // Group columns by the exact set of tables they appear in
        val groupedByTableSet = mutableMapOf<Set<String>, MutableList<String>>()
        for ((col, tableSet) in colToTables) {
            if (tableSet.size >= 2) {
                groupedByTableSet.getOrPut(tableSet) { mutableListOf() }.add(col)
            }
        }

        // Find the best group: most columns shared by the same set of tables (min 3 columns)
        val bestGroup = groupedByTableSet.entries
            .filter { it.value.size >= 3 }
            .maxByOrNull { it.value.size }
            ?: return emptyList()

        val bestSharedNames = bestGroup.value.toSet()

        // Use first matching table as reference for column types/order
        val refTable = tables.first { t ->
            t.columns.map { it.name.uppercase() }.toSet().containsAll(bestSharedNames)
        }

        return refTable.columns.filter { it.name.uppercase() in bestSharedNames }
    }

    fun findChildAndStandalone(
        tables: List<Table>,
        sharedColumns: List<Column>
    ): Pair<List<Table>, List<Table>> {
        val sharedNames = sharedColumns.map { it.name.uppercase() }.toSet()
        val children = tables.filter { t ->
            val cols = t.columns.map { it.name.uppercase() }.toSet()
            cols.containsAll(sharedNames) && t.columns.size > sharedNames.size
        }
        val standalone = tables - children.toSet()
        return Pair(children, standalone)
    }

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

    // --- File content builder with imports ---

    private fun buildFileContent(classCode: String, columns: List<Column>): String {
        val sb = StringBuilder()
        sb.appendLine("package dto;")
        sb.appendLine()

        val imports = mutableListOf<String>()
        if (columns.any { it.sqlType in listOf("DATE", "DATETIME", "TIMESTAMP") }) {
            imports.add("import java.time.LocalDate;")
        }

        if (imports.isNotEmpty()) {
            for (imp in imports) sb.appendLine(imp)
            sb.appendLine()
        }

        sb.append(classCode)
        return sb.toString()
    }

    // --- Code generation ---

    private fun generateAbstractClass(className: String, columns: List<Column>): String {
        val fields = columns.map { it.toJavaField() }
        return buildString {
            appendLine("public abstract class $className {")
            for ((type, name) in fields) appendLine("    private final $type $name;")
            appendLine()
            appendLine("    public $className(${fields.joinToString(", ") { "${it.first} ${it.second}" }}) {")
            for ((_, name) in fields) appendLine("        this.$name = $name;")
            appendLine("    }")
            appendLine()
            for ((type, name) in fields) {
                val getter = if (type == "boolean") "is${name.cap()}" else "get${name.cap()}"
                appendLine("    public $type $getter() {")
                appendLine("        return $name;")
                appendLine("    }")
                appendLine()
            }
            appendLine("    @Override")
            appendLine("    public String toString() {")
            appendLine("        return \"$className{\" +")
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
            appendLine("}")
        }
    }

    private fun generateChildClass(
        table: Table, parentName: String,
        sharedColumns: List<Column>, sharedNames: Set<String>
    ): String {
        val className = table.name.toPascalCase()
        val extraCols = table.columns.filter { it.name.uppercase() !in sharedNames }
        val extraFields = extraCols.map { it.toJavaField() }
        val parentFields = sharedColumns.map { it.toJavaField() }

        return buildString {
            appendLine("public class $className extends $parentName {")
            for ((type, name) in extraFields) appendLine("    private final $type $name;")
            appendLine()
            val allParams = (parentFields + extraFields).joinToString(", ") { "${it.first} ${it.second}" }
            appendLine("    public $className($allParams) {")
            appendLine("        super(${parentFields.joinToString(", ") { it.second }});")
            for ((_, name) in extraFields) appendLine("        this.$name = $name;")
            appendLine("    }")
            appendLine()
            for ((type, name) in extraFields) {
                val getter = if (type == "boolean") "is${name.cap()}" else "get${name.cap()}"
                appendLine("    public $type $getter() {")
                appendLine("        return $name;")
                appendLine("    }")
                appendLine()
            }
            appendLine("    @Override")
            appendLine("    public String toString() {")
            appendLine("        return super.toString() + \"$className{\" +")
            for ((i, f) in extraFields.withIndex()) {
                val comma = if (i == 0) "" else ", "
                if (f.first == "String") {
                    appendLine("                \"${comma}${f.second}='\" + ${f.second} + '\\'' +")
                } else {
                    appendLine("                \"${comma}${f.second}=\" + ${f.second} +")
                }
            }
            appendLine("                '}';")
            appendLine("    }")
            appendLine("}")
        }
    }

    private fun generateStandaloneClass(table: Table): String {
        val className = table.name.toPascalCase()
        val fields = table.columns.map { it.toJavaField() }

        if (fields.size <= 6) {
            val params = fields.joinToString(",\n                        ") { "${it.first} ${it.second}" }
            return "public record $className($params) {\n}"
        }

        return buildString {
            appendLine("public class $className {")
            for ((type, name) in fields) appendLine("    private final $type $name;")
            appendLine()
            appendLine("    public $className(${fields.joinToString(", ") { "${it.first} ${it.second}" }}) {")
            for ((_, name) in fields) appendLine("        this.$name = $name;")
            appendLine("    }")
            appendLine()
            for ((type, name) in fields) {
                val getter = if (type == "boolean") "is${name.cap()}" else "get${name.cap()}"
                appendLine("    public $type $getter() { return $name; }")
            }
            appendLine("}")
        }
    }

    // --- Helpers ---

    private fun Column.toJavaField(): Pair<String, String> = Pair(mapType(sqlType), name.toCamelCase())

    private fun mapType(sqlType: String): String = when (sqlType) {
        "INT", "INTEGER", "SMALLINT", "TINYINT" -> "int"
        "BIGINT" -> "long"
        "FLOAT", "REAL" -> "float"
        "DOUBLE", "DECIMAL", "NUMERIC" -> "double"
        "BOOLEAN", "BOOL" -> "boolean"
        "DATE", "DATETIME", "TIMESTAMP" -> "LocalDate"
        else -> "String"
    }

    private fun String.toPascalCase(): String {
        return replace(Regex("([\\p{Ll}])([\\p{Lu}])")) { "${it.groupValues[1]}_${it.groupValues[2]}" }
            .split("_")
            .joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
    }

    private fun String.toCamelCase(): String = toPascalCase().replaceFirstChar { it.lowercase() }

    private fun String.cap() = replaceFirstChar { it.uppercase() }
}