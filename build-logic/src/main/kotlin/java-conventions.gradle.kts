// Shared by every Java module of the build.
plugins {
    java
}

group = "by.akozel"
version = "1.0-SNAPSHOT"

// Java 25 is mandatory (see README): commands run on virtual threads, which need JDK 24+ (JEP 491) to wait for a
// pooled connection inside Axon's synchronized stream code without pinning their carrier thread.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
