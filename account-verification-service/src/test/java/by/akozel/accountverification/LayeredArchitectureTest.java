package by.akozel.accountverification;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * The layers of the production code (domain, application, infrastructure, presentation), the composition root
 * ({@link Application}) and the direction of the dependencies between them. Except for the presentation, which has
 * no entry points yet, layers are not optional: a rule fails when one of them has no classes, so it never passes by
 * checking nothing.
 */
class LayeredArchitectureTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("by.akozel.accountverification");

    /** Only the root package itself, without sub-packages. */
    private static final String COMPOSITION_ROOT = "by.akozel.accountverification";
    private static final String DOMAIN = "by.akozel.accountverification.domain..";
    private static final String APPLICATION = "by.akozel.accountverification.application..";
    private static final String INFRASTRUCTURE = "by.akozel.accountverification.infrastructure..";
    private static final String PRESENTATION = "by.akozel.accountverification.presentation..";

    private static final ArchRule LAYERS =
            layeredArchitecture().consideringOnlyDependenciesInLayers()
                                 .ensureAllClassesAreContainedInArchitecture()
                                 .layer("Composition root").definedBy(COMPOSITION_ROOT)
                                 .layer("Domain").definedBy(DOMAIN)
                                 .layer("Application").definedBy(APPLICATION)
                                 .layer("Infrastructure").definedBy(INFRASTRUCTURE)
                                 .optionalLayer("Presentation").definedBy(PRESENTATION)
                                 .whereLayer("Composition root").mayNotBeAccessedByAnyLayer()
                                 .whereLayer("Presentation").mayOnlyBeAccessedByLayers("Composition root")
                                 .whereLayer("Infrastructure").mayOnlyBeAccessedByLayers("Composition root")
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

    private static final ArchRule INFRASTRUCTURE_CONCERNS_ARE_INDEPENDENT =
            slices().matching("by.akozel.accountverification.infrastructure.(*)..")
                    .namingSlices("infrastructure.$1")
                    .should().notDependOnEachOther()
                    .because("each technical concern can be replaced on its own; Application combines them");

    @Test
    void layersDependOnlyInward() {
        LAYERS.check(PRODUCTION);
    }

    @Test
    void domainAndApplicationDoNotDependOnPersistenceTechnology() {
        CORE_IS_FREE_OF_PERSISTENCE_TECHNOLOGY.check(PRODUCTION);
    }

    @Test
    void infrastructureConcernsDoNotDependOnEachOther() {
        INFRASTRUCTURE_CONCERNS_ARE_INDEPENDENT.check(PRODUCTION);
    }
}
