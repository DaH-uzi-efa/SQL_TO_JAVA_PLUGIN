package no.huzef.blackhole.converter

import no.huzef.blackhole.converter.TypeMapper.capitalizeFirst
import no.huzef.blackhole.converter.TypeMapper.toCamelCase
import no.huzef.blackhole.converter.TypeMapper.toPascalCase

object DbServiceGenerator {

    private const val RECORD_FIELD_THRESHOLD = 6

    data class CrudOptions(
        val insert: Boolean,
        val select: Boolean,
        val update: Boolean,
        val delete: Boolean
    )

    fun generate(
        serviceName: String,
        tables: List<Table>,
        options: CrudOptions,
        sharedColumnNames: Set<String> = emptySet()
    ): String {
        return buildString {
            appendHeader(serviceName, tables)
            appendSqlConstants(tables, options)
            appendLine()
            appendConstructor(serviceName)
            appendLine()
            for (table in tables) {
                if (options.insert) { appendInsertMethod(table); appendLine() }
                if (options.select) { appendSelectAllMethod(table, sharedColumnNames); appendLine() }
                if (options.update) { appendUpdateMethod(table); appendLine() }
                if (options.delete) { appendDeleteMethod(table); appendLine() }
            }
            appendLine("}")
        }
    }

    private fun StringBuilder.appendHeader(serviceName: String, tables: List<Table>) {
        appendLine("package db;")
        appendLine()
        appendLine("import dto.*;")
        appendLine()
        appendLine("import java.sql.*;")
        if (tables.any { t -> t.columns.any { needsDateConversion(it) } }) {
            appendLine("import java.sql.Date;")
        }
        appendLine("import java.util.ArrayList;")
        appendLine("import java.util.List;")
        appendLine()
        appendLine("public class $serviceName {")
        appendLine()
    }

    private fun StringBuilder.appendConstructor(serviceName: String) {
        appendLine("    private final Connection connection;")
        appendLine()
        appendLine("    public $serviceName(Connection connection) {")
        appendLine("        this.connection = connection;")
        appendLine("    }")
    }

    private fun StringBuilder.appendSqlConstants(tables: List<Table>, options: CrudOptions) {
        for (table in tables) {
            val upper = table.name.uppercase()
            val pk = findPkColumn(table)

            if (options.insert) {
                val placeholders = table.columns.joinToString(", ") { "?" }
                appendLine("    private static final String INSERT_$upper = \"INSERT INTO ${table.name} VALUES ($placeholders)\";")
            }
            if (options.select) {
                appendLine("    private static final String SELECT_ALL_$upper = \"SELECT * FROM ${table.name}\";")
            }
            if (options.update && pk != null) {
                val setClause = table.columns
                    .filter { it.name != pk.name }
                    .joinToString(", ") { "${it.name} = ?" }
                appendLine("    private static final String UPDATE_$upper = \"UPDATE ${table.name} SET $setClause WHERE ${pk.name} = ?\";")
            }
            if (options.delete && pk != null) {
                appendLine("    private static final String DELETE_$upper = \"DELETE FROM ${table.name} WHERE ${pk.name} = ?\";")
            }
        }
    }

    private fun StringBuilder.appendInsertMethod(table: Table) {
        val className = table.name.toPascalCase()
        val param = paramName(className)
        val upper = table.name.uppercase()

        appendLine("    public void add$className($className $param) throws SQLException {")
        appendLine("        try (PreparedStatement stmt = connection.prepareStatement(INSERT_$upper)) {")
        for ((i, col) in table.columns.withIndex()) {
            appendLine("            stmt.${jdbcSetter(col)}(${i + 1}, ${accessorCall(col, param, table)});")
        }
        appendLine("            stmt.executeUpdate();")
        appendLine("        }")
        appendLine("    }")
    }

    private fun StringBuilder.appendSelectAllMethod(table: Table, sharedColumnNames: Set<String>) {
        val className = table.name.toPascalCase()
        val upper = table.name.uppercase()

        appendLine("    public List<$className> getAll$className() throws SQLException {")
        appendLine("        List<$className> result = new ArrayList<>();")
        appendLine("        try (PreparedStatement stmt = connection.prepareStatement(SELECT_ALL_$upper);")
        appendLine("             ResultSet rs = stmt.executeQuery()) {")
        appendLine("            while (rs.next()) {")

        val orderedCols = orderForConstructor(table, sharedColumnNames)
        val args = orderedCols.joinToString(",\n                        ") { col ->
            val getter = "rs.${jdbcGetter(col)}(\"${col.name}\")"
            if (needsDateConversion(col)) "$getter.toLocalDate()" else getter
        }

        appendLine("                result.add(new $className(")
        appendLine("                        $args")
        appendLine("                ));")
        appendLine("            }")
        appendLine("        }")
        appendLine("        return result;")
        appendLine("    }")
    }

    private fun StringBuilder.appendUpdateMethod(table: Table) {
        val className = table.name.toPascalCase()
        val param = paramName(className)
        val upper = table.name.uppercase()
        val pk = findPkColumn(table) ?: return

        appendLine("    public void update$className($className $param) throws SQLException {")
        appendLine("        try (PreparedStatement stmt = connection.prepareStatement(UPDATE_$upper)) {")
        val nonPkCols = table.columns.filter { it.name != pk.name }
        for ((i, col) in nonPkCols.withIndex()) {
            appendLine("            stmt.${jdbcSetter(col)}(${i + 1}, ${accessorCall(col, param, table)});")
        }
        appendLine("            stmt.${jdbcSetter(pk)}(${nonPkCols.size + 1}, ${accessorCall(pk, param, table)});")
        appendLine("            stmt.executeUpdate();")
        appendLine("        }")
        appendLine("    }")
    }

    private fun StringBuilder.appendDeleteMethod(table: Table) {
        val className = table.name.toPascalCase()
        val upper = table.name.uppercase()
        val pk = findPkColumn(table) ?: return
        val pkJavaType = TypeMapper.mapSqlTypeToJava(pk.sqlType)
        val pkJavaName = pk.name.toCamelCase()

        appendLine("    public void delete$className($pkJavaType $pkJavaName) throws SQLException {")
        appendLine("        try (PreparedStatement stmt = connection.prepareStatement(DELETE_$upper)) {")
        val value = if (needsDateConversion(pk)) "Date.valueOf($pkJavaName)" else pkJavaName
        appendLine("            stmt.${jdbcSetter(pk)}(1, $value);")
        appendLine("            stmt.executeUpdate();")
        appendLine("        }")
        appendLine("    }")
    }

    // --- Helpers ---

    /**
     * For child classes (tables with shared columns), reorders: parent fields first, then extra fields.
     * For records/standalone classes, keeps SQL order.
     */
    private fun orderForConstructor(table: Table, sharedColumnNames: Set<String>): List<Column> {
        val tableColsUpper = table.columns.map { it.name.uppercase() }.toSet()
        val isChild = sharedColumnNames.isNotEmpty() && tableColsUpper.containsAll(sharedColumnNames)

        if (!isChild) return table.columns

        val parentFields = table.columns.filter { it.name.uppercase() in sharedColumnNames }
        val extraFields = table.columns.filter { it.name.uppercase() !in sharedColumnNames }
        return parentFields + extraFields
    }

    private fun accessorCall(col: Column, paramName: String, table: Table): String {
        val javaName = col.name.toCamelCase()
        val javaType = TypeMapper.mapSqlTypeToJava(col.sqlType)
        val isRecord = table.columns.size <= RECORD_FIELD_THRESHOLD

        val accessor = when {
            isRecord -> "$paramName.$javaName()"
            javaType == "boolean" -> "$paramName.is${javaName.capitalizeFirst()}()"
            else -> "$paramName.get${javaName.capitalizeFirst()}()"
        }

        return if (needsDateConversion(col)) "Date.valueOf($accessor)" else accessor
    }

    private fun needsDateConversion(col: Column): Boolean =
        TypeMapper.mapSqlTypeToJava(col.sqlType) == "LocalDate"

    private fun paramName(className: String): String {
        return className.replaceFirstChar { it.lowercase() }
    }

    private fun jdbcSetter(col: Column): String = when (TypeMapper.mapSqlTypeToJava(col.sqlType)) {
        "int" -> "setInt"
        "long" -> "setLong"
        "float" -> "setFloat"
        "double" -> "setDouble"
        "boolean" -> "setBoolean"
        "LocalDate" -> "setDate"
        else -> "setString"
    }

    private fun jdbcGetter(col: Column): String = when (TypeMapper.mapSqlTypeToJava(col.sqlType)) {
        "int" -> "getInt"
        "long" -> "getLong"
        "float" -> "getFloat"
        "double" -> "getDouble"
        "boolean" -> "getBoolean"
        "LocalDate" -> "getDate"
        else -> "getString"
    }

    private fun findPkColumn(table: Table): Column? =
        table.columns.firstOrNull { it.isPrimaryKey } ?: table.columns.firstOrNull()
}