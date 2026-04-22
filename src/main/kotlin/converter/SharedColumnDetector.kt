package no.huzef.blackhole.converter

object SharedColumnDetector {

    private const val MIN_SHARED_COLUMNS = 3

    /**
     * Finds columns that are shared across multiple tables, indicating a potential
     * parent class in an inheritance hierarchy.
     */
    fun findSharedColumns(tables: List<Table>): List<Column> {
        if (tables.size < 2) return emptyList()

        val colToTables = mapColumnsToTables(tables)
        val groupedByTableSet = groupColumnsBySharedTables(colToTables)

        val bestGroup = groupedByTableSet.entries
            .filter { it.value.size >= MIN_SHARED_COLUMNS }
            .maxByOrNull { it.value.size }
            ?: return emptyList()

        val allShared = extractSharedColumns(tables, bestGroup.value.toSet())

        // Exclude primary keys — they belong on concrete classes, not abstract parents
        return allShared.filter { !it.isPrimaryKey }
    }

    /**
     * Separates tables into children (have all shared columns + extras) and
     * standalone (don't match the inheritance pattern).
     */
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

    private fun mapColumnsToTables(tables: List<Table>): Map<String, Set<String>> {
        val colToTables = mutableMapOf<String, MutableSet<String>>()
        for (t in tables) for (c in t.columns) {
            colToTables.getOrPut(c.name.uppercase()) { mutableSetOf() }.add(t.name)
        }
        return colToTables
    }

    private fun groupColumnsBySharedTables(
        colToTables: Map<String, Set<String>>
    ): Map<Set<String>, List<String>> {
        val grouped = mutableMapOf<Set<String>, MutableList<String>>()
        for ((col, tableSet) in colToTables) {
            if (tableSet.size >= 2) {
                grouped.getOrPut(tableSet) { mutableListOf() }.add(col)
            }
        }
        return grouped
    }

    private fun extractSharedColumns(tables: List<Table>, sharedNames: Set<String>): List<Column> {
        val refTable = tables.first { t ->
            t.columns.map { it.name.uppercase() }.toSet().containsAll(sharedNames)
        }
        return refTable.columns.filter { it.name.uppercase() in sharedNames }
    }
}

