package by.akozel.accountverification;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * The layers of the service (domain, application, infrastructure, presentation), the axon-foundation module, the
 * composition root ({@link Application}) and the direction of the dependencies between them. Domain, application,
 * foundation and composition root are not optional: the rule fails when one of them has no classes, so it never
 * passes by checking nothing. The service has no infrastructure or entry points of its own yet.
 */
class LayeredArchitectureTest {

    private static final String FOUNDATION_PACKAGE = "by.akozel.axon.foundation";

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(location -> !location.contains("-test-fixtures"))
            .importPackages("by.akozel.accountverification", FOUNDATION_PACKAGE);

    /** Only the root package itself, without sub-packages. */
    private static final String COMPOSITION_ROOT = "by.akozel.accountverification";
    private static final String DOMAIN = "by.akozel.accountverification.domain..";
    private static final String APPLICATION = "by.akozel.accountverification.application..";
    private static final String INFRASTRUCTURE = "by.akozel.accountverification.infrastructure..";
    private static final String PRESENTATION = "by.akozel.accountverification.presentation..";
    private static final String FOUNDATION = FOUNDATION_PACKAGE + "..";

    private static final ArchRule LAYERS =
            layeredArchitecture().consideringOnlyDependenciesInLayers()
                                 .ensureAllClassesAreContainedInArchitecture()
                                 .layer("Composition root").definedBy(COMPOSITION_ROOT)
                                 .layer("Domain").definedBy(DOMAIN)
                                 .layer("Application").definedBy(APPLICATION)
                                 .optionalLayer("Infrastructure").definedBy(INFRASTRUCTURE)
                                 .optionalLayer("Presentation").definedBy(PRESENTATION)
                                 .layer("Foundation").definedBy(FOUNDATION)
                                 .whereLayer("Composition root").mayNotBeAccessedByAnyLayer()
                                 .whereLayer("Presentation").mayOnlyBeAccessedByLayers("Composition root")
                                 .whereLayer("Infrastructure").mayOnlyBeAccessedByLayers("Composition root")
                                 .whereLayer("Foundation").mayOnlyBeAccessedByLayers("Infrastructure", "Composition root")
                                 .whereLayer("Application")
                                 .mayOnlyBeAccessedByLayers("Infrastructure", "Presentation", "Composition root")
                                 .whereLayer("Domain")
                                 .mayOnlyBeAccessedByLayers("Application", "Infrastructure", "Presentation",
                                                            "Composition root");

    private static final ArchRule CORE_IS_FREE_OF_PERSISTENCE_TECHNOLOGY =
            noClasses().that().resideInAnyPackage(DOMAIN, APPLICATION)
                       .should().dependOnClassesThat()
                       .resideInAnyPackage("jakarta.persistence..", "org.hibernate..", "com.zaxxer..",
                                           "org.postgresql..", "java.sql..", "javax.sql..")
                       .because("the database is a detail of the infrastructure layer");

    @Test
    void layersDependOnlyInward() {
        LAYERS.check(PRODUCTION);
    }

    @Test
    void domainAndApplicationDoNotDependOnPersistenceTechnology() {
        CORE_IS_FREE_OF_PERSISTENCE_TECHNOLOGY.check(PRODUCTION);
    }
}
