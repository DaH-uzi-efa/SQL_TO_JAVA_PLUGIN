package no.huzef.blackhole

object SqlToJavaConverter {

    data class Column(val name: String, val sqlType: String, val isNotNull: Boolean, val isPrimaryKey: Boolean)
    data class Table(val name: String, val columns: List<Column>, val foreignKeys: Map<String, String>)

    fun parseTables(sql: String): List<Table> {
        val regex = Regex(
            """CREATE\s+TABLE\s+`?(\w+)`?\s*\((.*?)\);""",
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
                        val fk = Regex("""FOREIGN\s+KEY\s*\((\w+)\)\s*REFERENCES\s+(\w+)""", RegexOption.IGNORE_CASE).find(line)
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

        val colCounts = mutableMapOf<String, Int>()
        for (t in tables) for (c in t.columns) {
            colCounts[c.name.uppercase()] = (colCounts[c.name.uppercase()] ?: 0) + 1
        }

        val threshold = (tables.size * 0.5).toInt().coerceAtLeast(2)
        val sharedNames = colCounts.filter { it.value >= threshold }.keys

        val ref = tables.firstOrNull { t ->
            t.columns.map { it.name.uppercase() }.toSet().containsAll(sharedNames)
        } ?: return emptyList()

        return ref.columns.filter { it.name.uppercase() in sharedNames }
    }

    fun generateWithInheritance(
        parentName: String,
        sharedColumns: List<Column>,
        childTables: List<Table>,
        standaloneTables: List<Table>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("package dto;\n")
        sb.appendLine(generateAbstractClass(parentName, sharedColumns))
        sb.appendLine()
        val sharedNames = sharedColumns.map { it.name.uppercase() }.toSet()
        for (child in childTables) {
            sb.appendLine(generateChildClass(child, parentName, sharedColumns, sharedNames))
            sb.appendLine()
        }
        for (table in standaloneTables) {
            sb.appendLine(generateStandaloneClass(table))
            sb.appendLine()
        }
        return sb.toString().trimEnd()
    }

    fun generateAll(tables: List<Table>): String {
        val sb = StringBuilder()
        sb.appendLine("package dto;\n")
        for (table in tables) {
            sb.appendLine(generateStandaloneClass(table))
            sb.appendLine()
        }
        return sb.toString().trimEnd()
    }

    fun generateFilesWithInheritance(
        parentName: String,
        sharedColumns: List<Column>,
        childTables: List<Table>,
        standaloneTables: List<Table>
    ): Map<String, String> {
        val files = mutableMapOf<String, String>()
        val sharedNames = sharedColumns.map { it.name.uppercase() }.toSet()

        files["$parentName.java"] = "package dto;\n\n" + generateAbstractClass(parentName, sharedColumns)

        for (child in childTables) {
            val className = child.name.toPascalCase()
            files["$className.java"] = "package dto;\n\n" + generateChildClass(child, parentName, sharedColumns, sharedNames)
        }

        for (table in standaloneTables) {
            val className = table.name.toPascalCase()
            files["$className.java"] = "package dto;\n\n" + generateStandaloneClass(table)
        }

        return files
    }

    fun generateAllFiles(tables: List<Table>): Map<String, String> {
        val files = mutableMapOf<String, String>()
        for (table in tables) {
            val className = table.name.toPascalCase()
            files["$className.java"] = "package dto;\n\n" + generateStandaloneClass(table)
        }
        return files
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
            appendLine("        return \"$className{\" +")
            for ((i, f) in extraFields.withIndex()) {
                val comma = if (i == 0) "" else ", "
                if (f.first == "String") {
                    appendLine("                \"${comma}${f.second}='\" + ${f.second} + '\\'' +")
                } else {
                    appendLine("                \"${comma}${f.second}=\" + ${f.second} +")
                }
            }
            appendLine("                \"} \" + super.toString();")
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
        "DATE", "DATETIME", "TIMESTAMP" -> "java.time.LocalDateTime"
        else -> "String"
    }

    private fun String.toPascalCase(): String {
        return replace(Regex("([a-z])([A-Z])")) { "${it.groupValues[1]}_${it.groupValues[2]}" }
            .split("_")
            .joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
    }

    private fun String.toCamelCase(): String = toPascalCase().replaceFirstChar { it.lowercase() }

    private fun String.cap() = replaceFirstChar { it.uppercase() }
}