package by.akozel.accountverification;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Function;

import by.akozel.accountverification.account.Account;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.commandhandling.gateway.CommandResult;
import org.axonframework.messaging.core.annotation.MessageHandler;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.junit.jupiter.api.Test;

/**
 * Architecture rules of the production code. Test classes are not checked: some break these rules on purpose, to
 * verify the safeguards behind them.
 */
class ArchitectureTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("by.akozel.accountverification");

    /** Classes with Axon message handlers: {@code @CommandHandler}, {@code @EventHandler}, ... are all meta-annotated. */
    private static final DescribedPredicate<JavaClass> DECLARE_MESSAGE_HANDLERS = DescribedPredicate.describe(
            "declare Axon message handlers",
            javaClass -> javaClass.getCodeUnits().stream().anyMatch(unit -> unit.isMetaAnnotatedWith(MessageHandler.class)));

    /** Any {@link CommandBus} method, and the {@link CommandGateway} sends that take no {@link ProcessingContext}. */
    private static final DescribedPredicate<JavaAccess<?>> SEND_A_COMMAND_WITHOUT_CONTEXT = DescribedPredicate.describe(
            "send a command without a ProcessingContext",
            access -> access.getTarget() instanceof AccessTarget.CodeUnitAccessTarget target
                    && (target.getOwner().isAssignableTo(CommandBus.class)
                    || target.getOwner().isAssignableTo(CommandGateway.class)
                    && target.getName().startsWith("send")
                    && target.getRawParameterTypes().stream()
                             .noneMatch(type -> type.isAssignableTo(ProcessingContext.class))));

    private static final ArchRule HANDLERS_SEND_COMMANDS_WITH_THEIR_CONTEXT =
            noClasses().that(DECLARE_MESSAGE_HANDLERS)
                       .should().accessTargetWhere(SEND_A_COMMAND_WITHOUT_CONTEXT)
                       .because("a command sent from a handler without the handler's ProcessingContext loses its "
                                        + "correlation data and escapes the transaction manager's check on the "
                                        + "context; use CommandDispatcher or CommandGateway.send(command, context)");

    private static final ArchRule NO_STATE_IN_THREAD_LOCALS =
            noClasses().should().dependOnClassesThat().areAssignableTo(ThreadLocal.class)
                       .because("state of a unit of work belongs in its ProcessingContext; a ThreadLocal does not "
                                        + "follow work that changes threads");

    @Test
    void handlersSendCommandsOnlyWithTheirProcessingContext() {
        HANDLERS_SEND_COMMANDS_WITH_THEIR_CONTEXT.check(PRODUCTION);
    }

    @Test
    void productionCodeKeepsNoStateInThreadLocals() {
        NO_STATE_IN_THREAD_LOCALS.check(PRODUCTION);
    }

    @Test
    void detectsAThreadLocal() {
        assertThat(NO_STATE_IN_THREAD_LOCALS.evaluate(new ClassFileImporter().importClasses(KeepsAThreadLocal.class))
                                            .hasViolation()).isTrue();
    }

    @Test
    void checksTheHandlersOfTheProductionCode() {
        assertThat(PRODUCTION.that(DECLARE_MESSAGE_HANDLERS).contain(Account.class)).isTrue();
    }

    @Test
    void detectsAHandlerThatSendsWithoutItsContext() {
        assertThat(violates(SendsAndWaitsWithoutContext.class)).isTrue();
        assertThat(violates(SendsWithoutContextByMethodReference.class)).isTrue();
    }

    @Test
    void acceptsAHandlerThatSendsWithItsContext() {
        assertThat(violates(SendsWithContext.class)).isFalse();
    }

    private static boolean violates(Class<?> handler) {
        return HANDLERS_SEND_COMMANDS_WITH_THEIR_CONTEXT.evaluate(new ClassFileImporter().importClasses(handler))
                                                        .hasViolation();
    }

    static class SendsAndWaitsWithoutContext {

        private final CommandGateway gateway;

        SendsAndWaitsWithoutContext(CommandGateway gateway) {
            this.gateway = gateway;
        }

        @EventHandler
        void on(Object event) {
            gateway.sendAndWait(event);
        }
    }

    static class SendsWithoutContextByMethodReference {

        private final Function<Object, CommandResult> send;

        SendsWithoutContextByMethodReference(CommandGateway gateway) {
            this.send = gateway::send;
        }

        @EventHandler
        void on(Object event) {
            send.apply(event);
        }
    }

    static class KeepsAThreadLocal {

        private final ThreadLocal<String> current = new ThreadLocal<>();

        String current() {
            return current.get();
        }
    }

    static class SendsWithContext {

        @EventHandler
        void on(Object event, CommandDispatcher dispatcher, CommandGateway gateway, ProcessingContext context) {
            dispatcher.send(event);
            gateway.send(event, context);
        }
    }
}
