plugins {
    id("java")
    application
}

group = "by.akozel"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

// Versions match the ones Axon Framework 5.2.x is built and tested against.
val axonVersion = "5.2.3"
val hibernateVersion = "7.4.4.Final"
val hikariVersion = "7.1.0"
val postgresqlVersion = "42.7.12"
val logbackVersion = "1.5.18"

dependencies {
    // Axon Framework 5 (open source only; no io.axoniq.framework artifacts)
    implementation(platform("org.axonframework:axon-framework-bom:$axonVersion"))
    implementation("org.axonframework:axon-messaging")
    implementation("org.axonframework:axon-modelling")
    implementation("org.axonframework:axon-eventsourcing")

    // Event store: AggregateBasedJpaEventStorageEngine on top of PostgreSQL
    implementation("org.hibernate.orm:hibernate-core:$hibernateVersion")
    implementation("com.zaxxer:HikariCP:$hikariVersion")
    // Compile scope: the transaction manager asks the driver whether PostgreSQL aborted a transaction
    implementation("org.postgresql:postgresql:$postgresqlVersion")

    runtimeOnly("ch.qos.logback:logback-classic:$logbackVersion")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.axonframework:axon-test")
    testImplementation("org.assertj:assertj-core:3.27.7")
    // Integration tests start their own PostgreSQL in a container (Podman or Docker)
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "by.akozel.accountverification.Application"
}

tasks.withType<JavaCompile> {
    // Axon Framework 5 requires Java 21+
    options.release = 21
}

// Loads KEY=VALUE pairs from .env (if present), so `./gradlew run` uses the same settings as compose.yaml.
// Real environment variables are not overridden. Tests don't read it: they start PostgreSQL with Testcontainers.
fun dotEnv(): Map<String, String> {
    val file = layout.projectDirectory.file(".env").asFile
    if (!file.exists()) return emptyMap()
    return file.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
        .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
        .filterKeys { System.getenv(it) == null }
}

tasks.named<JavaExec>("run") {
    environment(dotEnv())
}

tasks.test {
    useJUnitPlatform()
}
