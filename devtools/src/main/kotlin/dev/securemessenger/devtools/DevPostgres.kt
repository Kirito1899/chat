package dev.securemessenger.devtools

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.nio.file.Files
import java.nio.file.Path

fun main() {
    val root = System.getenv("DEV_PROJECT_ROOT") ?: error("DEV_PROJECT_ROOT is required")
    val dataDirectory = Path.of(root, ".local", "postgres-data")
    Files.createDirectories(dataDirectory)
    val password = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is required")
    require(password.matches(Regex("[0-9a-f]{64}"))) { "Dev DATABASE_PASSWORD must be a generated 64-character hex value" }
    val postgres = EmbeddedPostgres.builder()
        .setPort(5432)
        .setDataDirectory(dataDirectory)
        .setCleanDataDirectory(false)
        .start()

    postgres.postgresDatabase.connection.use { connection ->
        connection.createStatement().use { statement ->
            val role = statement.executeQuery("SELECT 1 FROM pg_roles WHERE rolname = 'messenger'")
            if (role.next()) {
                statement.execute("ALTER ROLE messenger WITH LOGIN PASSWORD '$password'")
            } else {
                statement.execute("CREATE ROLE messenger LOGIN PASSWORD '$password'")
            }
            val database = statement.executeQuery("SELECT 1 FROM pg_database WHERE datname = 'secure_messenger'")
            if (!database.next()) {
                statement.execute("CREATE DATABASE secure_messenger OWNER messenger")
            }
        }
    }
    Runtime.getRuntime().addShutdownHook(Thread { postgres.close() })
    println("PostgreSQL ready at 127.0.0.1:5432; database=secure_messenger")
    Thread.currentThread().join()
}
