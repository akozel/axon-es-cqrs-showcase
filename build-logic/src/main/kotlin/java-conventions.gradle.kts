// Shared by every Java module of the build.
plugins {
    java
    jacoco
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

// Test coverage of the module's main code by the module's own tests (see CLAUDE.md, Rules). The HTML report with
// highlighted sources lands in build/reports/jacoco/test/html/index.html; `check` fails below the minimum.
val minimumCoverage = "0.95".toBigDecimal()
val enforcedCounters = listOf("INSTRUCTION", "BRANCH", "LINE")

val coverage = extensions.create<CoverageExtension>("coverage")

jacoco {
    toolVersion = the<VersionCatalogsExtension>().named("libs").findVersion("jacoco").get().requiredVersion
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.withType<JacocoReportBase>().configureEach {
    classDirectories.setFrom(sourceSets.main.map { main ->
        main.output.classesDirs.asFileTree.matching { exclude(coverage.excludedClasses.get()) }
    })
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        html.required = true
        xml.required = true
        csv.required = false
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        // The module as a whole, and every class on its own: a well-tested class must not hide an untested one.
        // Per class, 95% of fewer than 20 branches (or lines) leaves no room for a single miss.
        for (scope in listOf("BUNDLE", "CLASS")) {
            rule {
                element = scope
                for (name in enforcedCounters) {
                    limit {
                        counter = name
                        minimum = minimumCoverage
                    }
                }
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
