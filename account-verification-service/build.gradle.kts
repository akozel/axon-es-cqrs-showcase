plugins {
    id("java-conventions")
    application
}

dependencies {
    // Event store, transactions and command execution; brings Axon's messaging and event sourcing along
    implementation(project(":axon-foundation"))
    // The domain uses Axon's modelling annotations directly
    implementation(platform(libs.axon.bom))
    implementation(libs.axon.modelling)
    implementation(libs.axon.eventsourcing)

    runtimeOnly(libs.logback.classic)

    testImplementation(testFixtures(project(":axon-foundation")))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.axon.test)
    testImplementation(libs.assertj.core)
    // Architecture rules, checked by plain JUnit tests (ArchitectureTest, LayeredArchitectureTest)
    testImplementation(libs.archunit)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass = "by.akozel.accountverification.Application"
}

coverage {
    // The entry point reads the real environment and then blocks forever; the parts it wires together are tested.
    excludedClasses.add("by/akozel/accountverification/Application.class")
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
