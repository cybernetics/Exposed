package org.jetbrains.exposed.sql.statements

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.isAutoInc
import org.jetbrains.exposed.sql.vendors.currentDialect
import java.sql.ResultSet
import java.util.*


open class BatchInsertStatement(table: Table, ignore: Boolean = false): InsertStatement<List<Map<Column<*>, Any>>>(table, ignore) {

    override val flushCache: Boolean = false
    // REVIEW
    override val isAlwaysBatch = true

    val data = ArrayList<MutableMap<Column<*>, Any?>>()

    fun addBatch() {
        if (data.isNotEmpty()) {
            data[data.size - 1] = LinkedHashMap(values)
            values.clear()
        }
        data.add(values)
        arguments = null
    }

    private var arguments: List<List<Pair<Column<*>, Any?>>>? = null
        get() = field ?: data.map { single ->
            valuesAndDefaults().keys.map {
                it to (single[it] ?: it.defaultValueFun?.invoke() ?: it.dbDefaultValue?.let { DefaultValueMarker })
            }
        }.apply { field = this }

    override fun arguments() = arguments!!.map { it.map { it.first.columnType to it.second }.filter { it.second != DefaultValueMarker} }

    override fun generatedKeyFun(rs: ResultSet, inserted: Int): List<Map<Column<*>, Any>>? {
        val autoGeneratedKeys = arrayListOf<MutableMap<Column<*>, Any>>()

        val firstAutoIncColumn = table.columns.firstOrNull { it.columnType.isAutoInc }
        if (firstAutoIncColumn != null) {
            while (rs.next()) {
                autoGeneratedKeys.add(hashMapOf(firstAutoIncColumn to rs.getObject(1)))
            }

            if (inserted > 1 && !currentDialect.supportsMultipleGeneratedKeys) {
                // H2/SQLite only returns one last generated key...
                (autoGeneratedKeys[0][firstAutoIncColumn] as? Number)?.toLong()?.let {
                    var id = it

                    while (autoGeneratedKeys.size < inserted) {
                        id -= 1
                        autoGeneratedKeys.add(0, hashMapOf(firstAutoIncColumn to id))
                    }
                }
            }

            /** FIXME: https://github.com/JetBrains/Exposed/issues/129
             *  doesn't work with MySQL `INSERT ... ON DUPLICATE UPDATE`
             */
//            assert(isIgnore || autoGeneratedKeys.isEmpty() || autoGeneratedKeys.size == inserted) {
//                "Number of autoincs (${autoGeneratedKeys.size}) doesn't match number of batch entries ($inserted)"
//            }
        }
        // REVIEW
        arguments!!.forEachIndexed { i, pairs ->
            pairs.forEach { (col, value) ->
                val itemIndx = i
                if (!col.columnType.isAutoInc) {
                    val map = autoGeneratedKeys.getOrElse(itemIndx) {
                        hashMapOf<Column<*>, Any>().apply {
                            autoGeneratedKeys.add(itemIndx, this)
                        }
                    }
                    if (col.defaultValueFun != null && value != null && data[itemIndx][col] == null) {
                        map[col] = value
                    }
                }
            }
        }
        return autoGeneratedKeys
    }
}
