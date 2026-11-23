plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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
    mainClass.set("io.ktor.server.netty.EngineMain")
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.hikari)
    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.spring.security.crypto)
    implementation(libs.bouncycastle)
    implementation(libs.commons.logging)
    implementation(libs.jakarta.mail)
    implementation(libs.logback)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.kotlinx.coroutines.test)
}

testing {
    suites {
        val integrationTest by registering(JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation(project())
                implementation(project(":shared"))
                implementation(platform(libs.zonky.binaries.bom))
                implementation(libs.zonky.embedded.postgres) {
                    exclude(group = "io.zonky.test.postgres", module = "embedded-postgres-binaries-linux-amd64")
                    exclude(group = "io.zonky.test.postgres", module = "embedded-postgres-binaries-linux-amd64-alpine")
                    exclude(group = "io.zonky.test.postgres", module = "embedded-postgres-binaries-darwin-amd64")
                }
                implementation(libs.zonky.binaries.windows)
                implementation(libs.postgresql)
                implementation(libs.ktor.client.cio)
                implementation(libs.kotlinx.serialization.json)
                implementation("org.jetbrains.kotlin:kotlin-test-junit5:${libs.versions.kotlin.get()}")
            }
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
