package no.huzef.blackhole.converter

data class Column(
    val name: String,
    val sqlType: String,
    val isNotNull: Boolean,
    val isPrimaryKey: Boolean
)

data class Table(
    val name: String,
    val columns: List<Column>,
    val foreignKeys: Map<String, String>
)

object SqlParser {

    private const val WORD = "[\\p{L}\\p{N}_]+"

    fun parseTables(sql: String): List<Table> {
        val regex = Regex(
            """CREATE\s+TABLE\s+`?($WORD)`?\s*\((.*?)\);""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        return regex.findAll(sql).map { match ->
            val tableName = match.groupValues[1]
            val body = match.groupValues[2]
            parseTableBody(tableName, body)
        }.toList()
    }

    private fun parseTableBody(tableName: String, body: String): Table {
        val columns = mutableListOf<Column>()
        val foreignKeys = mutableMapOf<String, String>()

        for (line in body.split(",").map { it.trim() }.filter { it.isNotBlank() }) {
            val upper = line.uppercase()
            when {
                upper.startsWith("PRIMARY KEY") -> {}
                upper.startsWith("FOREIGN KEY") -> parseForeignKey(line, foreignKeys)
                upper.startsWith("CONSTRAINT") || upper.startsWith("UNIQUE") ||
                        upper.startsWith("INDEX") || upper.startsWith("KEY") -> {}
                else -> parseColumn(line, columns)
            }
        }
        return Table(tableName, columns, foreignKeys)
    }

    private fun parseForeignKey(line: String, foreignKeys: MutableMap<String, String>) {
        val fk = Regex(
            """FOREIGN\s+KEY\s*\(($WORD)\)\s*REFERENCES\s+($WORD)""",
            RegexOption.IGNORE_CASE
        ).find(line)
        if (fk != null) foreignKeys[fk.groupValues[1]] = fk.groupValues[2]
    }

    private fun parseColumn(line: String, columns: MutableList<Column>) {
        val parts = line.split("\\s+".toRegex())
        if (parts.size < 2) return

        val colName = parts[0].replace("`", "")
        val sqlType = parts[1].uppercase().replace(Regex("\\(.*\\)"), "")
        val upper = line.uppercase()
        val isPk = upper.contains("PRIMARY KEY")
        val isNotNull = upper.contains("NOT NULL") || isPk

        columns.add(Column(colName, sqlType, isNotNull, isPk))
    }
}