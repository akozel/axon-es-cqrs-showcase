// Base configuration of Axon Framework 5 for the services of this repository: PostgreSQL event store through JPA,
// one transaction per unit of work, command execution on virtual threads. Knows nothing about any service.
plugins {
    id("java-conventions")
    `java-library`
    `java-test-fixtures`
}

dependencies {
    // In the public API: EventSourcingConfigurer, CommandBus, TransactionManager, EntityManagerFactory, HikariConfig
    api(platform(libs.axon.bom))
    api(libs.axon.messaging)
    api(libs.axon.eventsourcing)
    api(libs.jakarta.persistence.api)
    api(libs.hikari)

    // Event store: AggregateBasedJpaEventStorageEngine on top of PostgreSQL
    implementation(libs.hibernate.core)
    // Compile scope: the transaction manager asks the driver whether PostgreSQL aborted a transaction
    implementation(libs.postgresql)

    // Shared with the services' tests: a PostgreSQL container and the wiring of this module against it
    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.junit.jupiter.api)
    testFixturesApi(platform(libs.testcontainers.bom))
    testFixturesApi(libs.testcontainers.postgresql)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.axon.test)
    testImplementation(libs.assertj.core)
    testImplementation(libs.archunit)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.logback.classic)
}
