val kotlin_version: String by project
val logback_version: String by project
val ktor_version = "2.3.9"

plugins {
    kotlin("jvm") version "2.1.10"
    id("io.ktor.plugin") version "2.3.9"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.10"
}

group = "com.incodap"
version = "0.0.1"

application {
    mainClass.set("com.incodap.ApplicationKt")
}

repositories { mavenCentral() }

dependencies {
    // Ktor Server
    implementation("io.ktor:ktor-server-core:$ktor_version")
    implementation("io.ktor:ktor-server-auth:$ktor_version")
    implementation("io.ktor:ktor-server-auth-jwt:$ktor_version")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor_version")
    implementation("io.ktor:ktor-server-cors:$ktor_version")
    implementation("io.ktor:ktor-server-netty:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor_version")

    // ⬇️ Añadir estos dos:
    implementation("io.ktor:ktor-server-status-pages:$ktor_version")
    implementation("io.ktor:ktor-server-call-logging:$ktor_version")

    // (Opcional, solo si usas @Resource)
    // implementation("io.ktor:ktor-server-resources:$ktor_version")

    // Firebase
    implementation("com.google.firebase:firebase-admin:9.3.0")

    // Ktor Client
    implementation("io.ktor:ktor-client-core:$ktor_version")
    implementation("io.ktor:ktor-client-cio:$ktor_version")
    implementation("io.ktor:ktor-client-content-negotiation:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor_version")

    // Logging
    implementation("ch.qos.logback:logback-classic:$logback_version")
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")

    // Stripe API
    implementation("com.stripe:stripe-java:24.10.0")

    // JSON utils
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.json:json:20231013")

    // Cloudinary
    implementation("com.cloudinary:cloudinary-http44:1.37.0")

    // Apache POI - Excel
    implementation("org.apache.poi:poi-ooxml:5.2.3")

    // Seguridad
    implementation("org.mindrot:jbcrypt:0.4")

    // Koin
    implementation("io.insert-koin:koin-core:3.5.3")
    implementation("io.insert-koin:koin-ktor:3.5.3")
    implementation("io.insert-koin:koin-logger-slf4j:3.5.3")

    // ✅ mover a testImplementation
    testImplementation("io.ktor:ktor-server-test-host:$ktor_version")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
}
