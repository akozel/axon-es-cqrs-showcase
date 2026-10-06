package by.akozel.axon.foundation.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.RollbackException;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager;
import org.axonframework.messaging.core.unitofwork.transaction.jpa.JpaTransactionalExecutorProvider;
import org.hibernate.Session;
import org.hibernate.jdbc.ReturningWork;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the transaction handling logic against fake JPA objects, so failures that a real database cannot produce
 * on demand (a failing {@code begin}, {@code commit} or {@code rollback}, a rollback-only transaction) are covered
 * deterministically.
 */
class JpaUnitOfWorkTransactionManagerTest {

    private static final CommandMessage COMMAND = new GenericCommandMessage(new MessageType("test.Ping"), "ping");

    private final FakeJpa jpa = new FakeJpa();
    private final JpaUnitOfWorkTransactionManager manager = new JpaUnitOfWorkTransactionManager(jpa.factory());
    private final UnitOfWorkFactory unitsOfWork = MessagingConfigurer
            .create()
            .componentRegistry(registry -> registry.registerComponent(TransactionManager.class, config -> manager))
            .build()
            .getComponent(UnitOfWorkFactory.class);

    @Nested
    class WhenUnitOfWorkSucceeds {

        @Test
        void doesNotTouchTheDatabaseWhenTheEntityManagerIsNeverUsed() throws Exception {
            // when
            run(context -> "result");

            // then
            assertThat(jpa.calls).isEmpty();
        }

        @Test
        void beginsOnFirstUseCommitsAndClosesInThatOrder() throws Exception {
            // when
            run(context -> useEntityManager(context));

            // then
            assertThat(jpa.calls).containsExactly("createEntityManager", "begin", "commit", "close");
        }

        @Test
        void usesTheSameEntityManagerForTheWholeUnitOfWork() throws Exception {
            // when
            run(context -> {
                EntityManager first = useEntityManager(context);
                EntityManager second = useEntityManager(context);
                assertThat(second).isSameAs(first);
                return null;
            });

            // then
            assertThat(jpa.calls).containsExactly("createEntityManager", "begin", "commit", "close");
        }

        @Test
        void commitsOnADatabaseOtherThanPostgres() throws Exception {
            // given: its connection cannot tell whether the database aborted the transaction
            jpa.hibernateOnAnotherDatabase = true;

            // when
            run(context -> useEntityManager(context));

            // then
            assertThat(jpa.calls).containsExactly("createEntityManager", "begin", "commit", "close");
        }
    }

    @Nested
    class WhenUnitOfWorkFails {

        @Test
        void neitherRollsBackNorClosesAnEntityManagerThatWasClosedElsewhere() {
            // when
            failureOf(context -> {
                useEntityManager(context).close();
                throw new IllegalStateException("boom");
            });

            // then: the only close is the body's own
            assertThat(jpa.calls).containsExactly("createEntityManager", "begin", "close");
        }

        @Test
        void keepsAFailedRollbackOfARollbackOnlyTransactionWithTheFailure() {
            // given
            jpa.rollbackOnly = true;
            jpa.rollbackFails = true;

            // when
            Throwable failure = failureOf(context -> useEntityManager(context));

            // then
            Throwable rollbackOnly = failure;
            while (rollbackOnly != null && !(rollbackOnly instanceof RollbackException)) {
                rollbackOnly = rollbackOnly.getCause();
            }
            assertThat(rollbackOnly).hasMessageContaining("rollback-only");
            assertThat(rollbackOnly.getSuppressed())
                    .singleElement(InstanceOfAssertFactories.THROWABLE)
                    .hasMessage("rollback failed");
            assertThat(jpa.calls).doesNotContain("commit").endsWith("close");
        }

        @Test
        void rollsBackAndClosesWhenTheBodyFailsAfterUsingTheEntityManager() {
            // when
            Throwable failure = failureOf(context -> {
                useEntityManager(context);
                throw new IllegalStateException("boom");
            });

            // then
            assertThat(failure).hasMessageContaining("boom");
            assertThat(jpa.calls).containsExactly("createEntityManager", "begin", "rollback", "close");
        }

        @Test
        void doesNotTouchTheDatabaseWhenTheBodyFailsBeforeUsingTheEntityManager() {
            // when
            failureOf(context -> {
                throw new IllegalStateException("boom");
            });

            // then
            assertThat(jpa.calls).isEmpty();
        }

        @Test
        void closesTheEntityManagerWhenBeginFails() {
            // given
            jpa.beginFails = true;

            // when
            Throwable failure = failureOf(context -> useEntityManager(context));

            // then
            assertThat(failure).hasStackTraceContaining("begin failed");
            assertThat(jpa.calls).containsExactly("createEntityManager", "begin", "close");
        }

        @Test
        void failsAndClosesWhenCommitFails() {
            // given
            jpa.commitFails = true;

            // when
            Throwable failure = failureOf(context -> useEntityManager(context));

            // then
            assertThat(failure).hasStackTraceContaining("commit failed");
            assertThat(jpa.calls).endsWith("close");
            assertThat(jpa.calls).filteredOn("close"::equals).hasSize(1);
        }

        @Test
        void failsInsteadOfSilentlyLosingDataWhenTheTransactionIsRollbackOnly() {
            // given
            jpa.rollbackOnly = true;

            // when
            Throwable failure = failureOf(context -> useEntityManager(context));

            // then
            assertThat(failure).hasStackTraceContaining("rollback-only");
            assertThat(jpa.calls).doesNotContain("commit");
            assertThat(jpa.calls).containsSubsequence("begin", "rollback", "close");
        }

        @Test
        void refusesToBeginATransactionThatWouldNeverBeCommitted() {
            // when: the entity manager is first needed after the commit step
            Throwable failure = failureOf(context -> {
                context.runOnAfterCommit(c -> useEntityManager(c));
                return null;
            });

            // then
            assertThat(failure).hasStackTraceContaining("already committed or rolled back");
            assertThat(jpa.calls).isEmpty();
        }

        @Test
        void closesTheEntityManagerEvenWhenRollbackFails() {
            // given
            jpa.rollbackFails = true;

            // when
            Throwable failure = failureOf(context -> {
                useEntityManager(context);
                throw new IllegalStateException("boom");
            });

            // then
            assertThat(failure).hasMessageContaining("boom");
            assertThat(jpa.calls).endsWith("close");
            assertThat(jpa.calls).filteredOn("close"::equals).hasSize(1);
        }
    }

    @Nested
    class OnAThreadThatAlreadyHoldsATransaction {

        @Test
        void allowsNestedWorkThatNeedsNoConnection() throws Exception {
            // when
            String nested = run(context -> {
                useEntityManager(context);
                try {
                    return run(inner -> "no connection needed");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });

            // then
            assertThat(nested).isEqualTo("no connection needed");
        }

        @Test
        void allowsTheNextTransactionOnceTheFirstHasCommitted() throws Exception {
            // when
            run(context -> useEntityManager(context));
            run(context -> useEntityManager(context));

            // then
            assertThat(jpa.calls).containsExactly("createEntityManager", "begin", "commit", "close",
                                                  "createEntityManager", "begin", "commit", "close");
        }
    }

    @Nested
    class CommandsSentWithTheContextOfAUnitOfWork {

        private final MessageDispatchInterceptor<CommandMessage> interceptor = manager.commandDispatchInterceptor();

        @Test
        void passBeforeTheUnitOfWorkWrites() throws Exception {
            // when
            boolean passed = run(context -> passes(context));

            // then
            assertThat(passed).isTrue();
        }

        @Test
        void areRefusedWhileItsTransactionIsOpen() throws Exception {
            // when
            boolean passed = run(context -> {
                useEntityManager(context);
                return passes(context);
            });

            // then
            assertThat(passed).isFalse();
        }

        @Test
        void areRefusedAlsoWhenSentFromAnotherThread() throws Exception {
            // when
            boolean passed = run(context -> {
                useEntityManager(context);
                return CompletableFuture.supplyAsync(() -> passes(context)).join();
            });

            // then
            assertThat(passed).isFalse();
        }

        @Test
        void passOnceItsTransactionHasCommitted() throws Exception {
            // given
            AtomicReference<Boolean> passedAfterCommit = new AtomicReference<>();

            // when
            run(context -> {
                useEntityManager(context);
                context.runOnAfterCommit(c -> passedAfterCommit.set(passes(c)));
                return null;
            });

            // then
            assertThat(passedAfterCommit.get()).isTrue();
        }

        @Test
        void doNotIncludeCommandsSentWithoutAContext() throws Exception {
            // when: nothing tells which unit of work, if any, sent it
            boolean passed = run(context -> {
                useEntityManager(context);
                return passes(null);
            });

            // then
            assertThat(passed).isTrue();
        }

        @Test
        void passFromAUnitOfWorkThatThisManagerDoesNotManage() throws Exception {
            // given
            UnitOfWorkFactory unmanaged = MessagingConfigurer.create().build().getComponent(UnitOfWorkFactory.class);

            // when
            boolean passed = unmanaged.create()
                                      .executeWithResult(context -> CompletableFuture.completedFuture(passes(context)))
                                      .get(10, TimeUnit.SECONDS);

            // then
            assertThat(passed).isTrue();
        }

        /** Whether the interceptor passes a command sent with the given context on; a refusal must explain itself. */
        private boolean passes(ProcessingContext context) {
            AtomicBoolean proceeded = new AtomicBoolean();
            MessageStream<?> result = interceptor.interceptOnDispatch(COMMAND, context, (message, c) -> {
                proceeded.set(true);
                return MessageStream.empty();
            });
            if (!proceeded.get()) {
                assertThat(result.error()).get(InstanceOfAssertFactories.THROWABLE)
                                          .hasMessageContaining("was sent with the processing context");
            }
            return proceeded.get();
        }
    }

    @Test
    void requiresSameThreadInvocationsBecauseAnEntityManagerIsNotThreadSafe() {
        assertThat(manager.requiresSameThreadInvocations()).isTrue();
    }

    @Test
    void refusesStartTransactionBecauseNobodyCouldUseTheEntityManagerItWouldBegin() {
        assertThatThrownBy(manager::startTransaction).isInstanceOf(UnsupportedOperationException.class);
        assertThat(jpa.calls).isEmpty();
    }

    private <R> R run(Function<ProcessingContext, R> body) throws Exception {
        return unitsOfWork.create()
                          .executeWithResult(context -> CompletableFuture.completedFuture(body.apply(context)))
                          .get(10, TimeUnit.SECONDS);
    }

    private Throwable failureOf(Function<ProcessingContext, ?> body) {
        try {
            run(body);
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (Exception e) {
            return e;
        }
        throw new AssertionError("Expected the unit of work to fail, but it succeeded");
    }

    private static EntityManager useEntityManager(ProcessingContext context) {
        return context.getResource(JpaTransactionalExecutorProvider.SUPPLIER_KEY)
                      .get()
                      .apply(entityManager -> entityManager)
                      .join();
    }

    /** Records every call that matters for transaction handling, and can be told to fail. */
    private static final class FakeJpa {

        final List<String> calls = new CopyOnWriteArrayList<>();
        volatile boolean beginFails;
        volatile boolean commitFails;
        volatile boolean rollbackFails;
        volatile boolean rollbackOnly;
        /** Unwraps to a Hibernate session whose JDBC connection is not PostgreSQL's; otherwise no Hibernate at all. */
        volatile boolean hibernateOnAnotherDatabase;

        private volatile boolean open;

        private final Connection otherDatabase = proxy(Connection.class, (proxy, method, args) -> {
            if (method.getName().equals("isWrapperFor")) {
                return false;
            }
            throw new UnsupportedOperationException(method.toString());
        });

        private final Session session = proxy(Session.class, (proxy, method, args) -> {
            if (method.getName().equals("doReturningWork")) {
                return ((ReturningWork<?>) args[0]).execute(otherDatabase);
            }
            throw new UnsupportedOperationException(method.toString());
        });
        private volatile boolean active;

        private final EntityTransaction transaction = proxy(EntityTransaction.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "begin" -> {
                    calls.add("begin");
                    if (beginFails) {
                        throw new IllegalStateException("begin failed");
                    }
                    active = true;
                    return null;
                }
                case "commit" -> {
                    calls.add("commit");
                    if (commitFails) {
                        throw new IllegalStateException("commit failed");
                    }
                    active = false;
                    return null;
                }
                case "rollback" -> {
                    calls.add("rollback");
                    if (rollbackFails) {
                        throw new IllegalStateException("rollback failed");
                    }
                    active = false;
                    return null;
                }
                case "isActive" -> {
                    return active;
                }
                case "getRollbackOnly" -> {
                    return rollbackOnly;
                }
                default -> throw new UnsupportedOperationException(method.toString());
            }
        });

        private final EntityManager entityManager = proxy(EntityManager.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getTransaction" -> {
                    return transaction;
                }
                case "isOpen" -> {
                    return open;
                }
                case "close" -> {
                    calls.add("close");
                    open = false;
                    return null;
                }
                case "toString" -> {
                    return "FakeEntityManager";
                }
                case "unwrap" -> {
                    if (hibernateOnAnotherDatabase && args[0] == Session.class) {
                        return session;
                    }
                    throw new PersistenceException("not a Hibernate session");
                }
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "equals" -> {
                    return proxy == args[0];
                }
                default -> throw new UnsupportedOperationException(method.toString());
            }
        });

        EntityManagerFactory factory() {
            return proxy(EntityManagerFactory.class, (proxy, method, args) -> {
                if (method.getName().equals("createEntityManager")) {
                    calls.add("createEntityManager");
                    open = true;
                    return entityManager;
                }
                throw new UnsupportedOperationException(method.toString());
            });
        }

        private static <T> T proxy(Class<T> type, InvocationHandler handler) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
        }
    }
}
