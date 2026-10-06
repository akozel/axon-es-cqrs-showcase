package by.akozel.axon.foundation;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Architecture rules of this module's production code; test classes and fixtures are not checked.
 */
class FoundationArchitectureTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(location -> !location.contains("/testFixtures/")
                    && !location.contains("-test-fixtures"))
            .importPackages("by.akozel.axon.foundation");

    private static final ArchRule CONCERNS_ARE_INDEPENDENT =
            slices().matching("by.akozel.axon.foundation.(*)..")
                    .namingSlices("foundation.$1")
                    .should().notDependOnEachOther()
                    .because("each technical concern can be replaced on its own; a service's composition root "
                                     + "combines them");

    private static final ArchRule NO_STATE_IN_THREAD_LOCALS =
            noClasses().should().dependOnClassesThat().areAssignableTo(ThreadLocal.class)
                       .because("state of a unit of work belongs in its ProcessingContext; a ThreadLocal does not "
                                        + "follow work that changes threads");

    @Test
    void persistenceAndMessagingDoNotDependOnEachOther() {
        CONCERNS_ARE_INDEPENDENT.check(PRODUCTION);
    }

    @Test
    void keepsNoStateInThreadLocals() {
        NO_STATE_IN_THREAD_LOCALS.check(PRODUCTION);
    }
}
