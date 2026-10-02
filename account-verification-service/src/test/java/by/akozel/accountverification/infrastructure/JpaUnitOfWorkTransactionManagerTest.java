package by.akozel.accountverification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.PersistenceException;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager;
import org.axonframework.messaging.core.unitofwork.transaction.jpa.JpaTransactionalExecutorProvider;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the transaction handling logic against fake JPA objects, so failures that a real database cannot produce
 * on demand (a failing {@code begin}, {@code commit} or {@code rollback}, a rollback-only transaction) are covered
 * deterministically, as is the refusal of a second connection on a thread that holds a transaction.
 */
class JpaUnitOfWorkTransactionManagerTest {

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
    }

    @Nested
    class WhenUnitOfWorkFails {

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
        void refusesTheConnectionANestedUnitOfWorkWouldNeed() throws Exception {
            // given
            AtomicReference<Throwable> nested = new AtomicReference<>();

            // when: a unit of work started on the same thread wants a transaction of its own
            run(context -> {
                useEntityManager(context);
                nested.set(failureOf(inner -> useEntityManager(inner)));
                return null;
            });

            // then: it fails at once, and only the outer unit of work touched the database
            assertThat(nested.get()).hasStackTraceContaining("already holds the open transaction");
            assertThat(jpa.calls).containsExactly("createEntityManager", "begin", "commit", "close");
        }

        @Test
        void refusesAShortTransactionOfItsOwn() throws Exception {
            // given
            AtomicReference<Throwable> standalone = new AtomicReference<>();

            // when: e.g. the event storage engine reading events, which it does outside any unit of work
            run(context -> {
                useEntityManager(context);
                standalone.set(catchThrowable(
                        () -> manager.executorProvider().getTransactionalExecutor(null).apply(em -> em).join()));
                return null;
            });

            // then
            assertThat(standalone.get()).hasStackTraceContaining("already holds the open transaction");
            assertThat(jpa.calls).containsExactly("createEntityManager", "begin", "commit", "close");
        }

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

        private volatile boolean open;
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
                case "unwrap" -> throw new PersistenceException("not a Hibernate session");
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
