package dev.securemessenger.server.storage

import org.flywaydb.core.Flyway
import javax.sql.DataSource

class DatabaseFactory(private val dataSource: DataSource) {
    fun migrate() {
        Flyway.configure()
            .dataSource(dataSource)
            .load()
            .migrate()
    }
}

fun <T> DataSource.transaction(block: (java.sql.Connection) -> T): T = connection.use { connection ->
    connection.autoCommit = false
    try {
        val result = block(connection)
        connection.commit()
        result
    } catch (error: Throwable) {
        connection.rollback()
        throw error
    } finally {
        connection.autoCommit = true
    }
}
