package no.huzef.blackhole.converter

object TypeMapper {

    fun mapSqlTypeToJava(sqlType: String): String = when (sqlType) {
        "INT", "INTEGER", "SMALLINT", "TINYINT" -> "int"
        "BIGINT" -> "long"
        "FLOAT", "REAL" -> "float"
        "DOUBLE", "DECIMAL", "NUMERIC" -> "double"
        "BOOLEAN", "BOOL" -> "boolean"
        "DATE", "DATETIME", "TIMESTAMP" -> "LocalDate"
        else -> "String"
    }

    fun requiresLocalDateImport(columns: List<Column>): Boolean {
        return columns.any { it.sqlType in listOf("DATE", "DATETIME", "TIMESTAMP") }
    }

    fun Column.toJavaField(): Pair<String, String> {
        return Pair(mapSqlTypeToJava(sqlType), name.toCamelCase())
    }

    /**
     * Replaces non-ASCII European characters with ASCII equivalents to ensure valid,
     * portable Java identifiers. Supports Scandinavian, German, Icelandic, and common
     * Romance language diacritics.
     */
    private fun String.normalizeToAscii(): String {
        val replacements = mapOf(
            // Norwegian / Danish
            "æ" to "ae", "Æ" to "Ae",
            "ø" to "oe", "Ø" to "Oe",
            "å" to "aa", "Å" to "Aa",
            // Swedish / German / Finnish (ä, ö shared)
            "ä" to "ae", "Ä" to "Ae",
            "ö" to "oe", "Ö" to "Oe",
            "ü" to "ue", "Ü" to "Ue",
            "ß" to "ss",
            // Icelandic
            "þ" to "th", "Þ" to "Th",
            "ð" to "d", "Ð" to "D",
            // Common Romance/Latin diacritics (stripped)
            "á" to "a", "Á" to "A",
            "é" to "e", "É" to "E",
            "í" to "i", "Í" to "I",
            "ó" to "o", "Ó" to "O",
            "ú" to "u", "Ú" to "U",
            "ý" to "y", "Ý" to "Y",
            "à" to "a", "À" to "A",
            "è" to "e", "È" to "E",
            "ì" to "i", "Ì" to "I",
            "ò" to "o", "Ò" to "O",
            "ù" to "u", "Ù" to "U",
            "â" to "a", "Â" to "A",
            "ê" to "e", "Ê" to "E",
            "î" to "i", "Î" to "I",
            "ô" to "o", "Ô" to "O",
            "û" to "u", "Û" to "U",
            "ç" to "c", "Ç" to "C",
            "ñ" to "n", "Ñ" to "N"
        )
        var result = this
        for ((from, to) in replacements) result = result.replace(from, to)
        return result
    }

    fun String.toPascalCase(): String {
        return normalizeToAscii()
            .replace(Regex("([\\p{Ll}])([\\p{Lu}])")) { "${it.groupValues[1]}_${it.groupValues[2]}" }
            .split("_")
            .joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
    }

    fun String.toCamelCase(): String = toPascalCase().replaceFirstChar { it.lowercase() }

    fun String.capitalizeFirst(): String = replaceFirstChar { it.uppercase() }
}