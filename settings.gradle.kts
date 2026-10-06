pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "axon-es-cqrs-showcase"

include("axon-foundation")
include("account-verification-service")
