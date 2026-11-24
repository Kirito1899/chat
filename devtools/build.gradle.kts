plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("dev.securemessenger.devtools.DevPostgresKt")
}

tasks.register<JavaExec>("verifyCiphertext") {
    group = "verification"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.securemessenger.devtools.VerifyCiphertextKt")
}

dependencies {
    implementation(platform(libs.zonky.binaries.bom))
    implementation(libs.zonky.embedded.postgres) {
        exclude(group = "io.zonky.test.postgres", module = "embedded-postgres-binaries-linux-amd64")
        exclude(group = "io.zonky.test.postgres", module = "embedded-postgres-binaries-linux-amd64-alpine")
        exclude(group = "io.zonky.test.postgres", module = "embedded-postgres-binaries-darwin-amd64")
    }
    implementation(libs.zonky.binaries.windows)
    implementation(libs.postgresql)
    implementation(libs.logback)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}
