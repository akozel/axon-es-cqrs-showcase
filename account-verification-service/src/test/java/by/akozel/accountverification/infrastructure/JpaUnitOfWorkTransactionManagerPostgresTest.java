package by.akozel.accountverification.infrastructure;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import by.akozel.accountverification.account.CreateAccount;
import by.akozel.accountverification.support.PostgresAxonEnvironment;
import by.akozel.accountverification.support.RequiresPostgresContainer;
import jakarta.persistence.EntityManager;
import org.axonframework.eventsourcing.eventstore.jpa.AggregateEventEntry;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.transaction.jpa.JpaTransactionalExecutorProvider;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies {@link JpaUnitOfWorkTransactionManager} against a real PostgreSQL: what is committed, what is rolled back,
 * and that nothing (transaction, entity manager, connection) is left behind. Runs against the PostgreSQL test
 * container.
 */
@RequiresPostgresContainer
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class JpaUnitOfWorkTransactionManagerPostgresTest {

    private static PostgresAxonEnvironment env;

    @BeforeAll
    static void start() {
        env = PostgresAxonEnvironment.start();
    }

    @AfterAll
    static void stop() {
        if (env != null) {
            env.close();
        }
    }

    @AfterEach
    void nothingIsLeftOpen() {
        assertThat(env.openTransactions()).as("sessions idle in transaction").isZero();
        assertThat(env.activeConnections()).as("connections not returned to the pool").isZero();
    }

    @Nested
    class WhenUnitOfWorkSucceeds {

        @Test
        void commitsWhatWasWrittenThroughItsEntityManager() throws Exception {
            // given
            String id = UUID.randomUUID().toString();

            // when
            env.unitOfWork(context -> persistAndFlush(context, rowOf(id))).get(30, TimeUnit.SECONDS);

            // then
            assertThat(env.countEventsOfAggregate(id)).isEqualTo(1);
        }

        @Test
        void usesOneEntityManagerWithAnActiveTransactionThroughoutAndClosesIt() throws Exception {
            // given
            AtomicReference<EntityManager> first = new AtomicReference<>();
            AtomicReference<EntityManager> second = new AtomicReference<>();

            // when
            env.unitOfWork(context -> {
                first.set(entityManagerOf(context));
                second.set(entityManagerOf(context));
                assertThat(first.get().getTransaction().isActive()).isTrue();
                return null;
            }).get(30, TimeUnit.SECONDS);

            // then
            assertThat(second.get()).isSameAs(first.get());
            assertThat(first.get().isOpen()).isFalse();
        }
    }

    @Nested
    class WhenUnitOfWorkFails {

        @Test
        void rollsBackWhenTheBodyFailsAfterFlush() {
            // given
            String id = UUID.randomUUID().toString();
            AtomicReference<EntityManager> used = new AtomicReference<>();

            // when
            CompletableFuture<Object> unitOfWork = env.unitOfWork(context -> {
                used.set(entityManagerOf(context));
                persistAndFlush(context, rowOf(id));
                throw new IllegalStateException("boom after flush");
            });

            // then
            assertThatThrownBy(() -> unitOfWork.get(30, TimeUnit.SECONDS)).hasStackTraceContaining("boom after flush");
            assertThat(env.countEventsOfAggregate(id)).isZero();
            assertThat(used.get().isOpen()).isFalse();
        }

        @Test
        void rollsBackWhenAFurtherPrepareCommitStepFailsAfterFlush() {
            // given
            String id = UUID.randomUUID().toString();
            AtomicReference<EntityManager> used = new AtomicReference<>();

            // when
            CompletableFuture<Object> unitOfWork = env.unitOfWork(context -> {
                used.set(entityManagerOf(context));
                persistAndFlush(context, rowOf(id));
                context.onPrepareCommit(c -> CompletableFuture.failedFuture(
                        new IllegalStateException("prepare commit failed")));
                return null;
            });

            // then
            assertThatThrownBy(() -> unitOfWork.get(30, TimeUnit.SECONDS))
                    .hasStackTraceContaining("prepare commit failed");
            assertThat(env.countEventsOfAggregate(id)).isZero();
            assertThat(used.get().isOpen()).isFalse();
        }

        @Test
        void rollsBackWhenFlushItselfViolatesAConstraint() {
            // given: the same (aggregate id, sequence number) twice in one transaction
            String id = UUID.randomUUID().toString();

            // when
            CompletableFuture<Object> unitOfWork = env.unitOfWork(context -> {
                persistAndFlush(context, rowOf(id));
                persistAndFlush(context, rowOf(id));
                return null;
            });

            // then
            assertThatThrownBy(() -> unitOfWork.get(30, TimeUnit.SECONDS)).isNotNull();
            assertThat(env.countEventsOfAggregate(id)).isZero();
        }

        @Test
        void keepsCommittedDataWhenSomethingFailsAfterCommit() {
            // given
            String id = UUID.randomUUID().toString();
            AtomicReference<EntityManager> used = new AtomicReference<>();

            // when
            CompletableFuture<Object> unitOfWork = env.unitOfWork(context -> {
                used.set(entityManagerOf(context));
                persistAndFlush(context, rowOf(id));
                context.onAfterCommit(c -> CompletableFuture.failedFuture(
                        new IllegalStateException("after commit failed")));
                return null;
            });

            // then: the unit of work reports the failure, but the transaction was already committed, and
            // nothing is left open
            assertThatThrownBy(() -> unitOfWork.get(30, TimeUnit.SECONDS))
                    .hasStackTraceContaining("after commit failed");
            assertThat(env.countEventsOfAggregate(id)).isEqualTo(1);
            assertThat(used.get().isOpen()).isFalse();
        }
    }

    @Nested
    class Isolation {

        @Test
        void keepsUncommittedDataInvisibleToOtherUnitsOfWork() throws Exception {
            // given: unit of work A has flushed a row but is held back before commit
            String id = UUID.randomUUID().toString();
            CountDownLatch flushed = new CountDownLatch(1);
            CountDownLatch mayFinish = new CountDownLatch(1);
            AtomicReference<EntityManager> entityManagerOfA = new AtomicReference<>();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> a = executor.submit(() -> env.unitOfWork(context -> {
                    entityManagerOfA.set(entityManagerOf(context));
                    persistAndFlush(context, rowOf(id));
                    flushed.countDown();
                    awaitUninterruptibly(mayFinish);
                    return null;
                }).get(30, TimeUnit.SECONDS));
                assertThat(flushed.await(30, TimeUnit.SECONDS)).isTrue();

                // when: unit of work B looks for the row
                AtomicReference<EntityManager> entityManagerOfB = new AtomicReference<>();
                Long visibleToB = env.unitOfWork(context -> {
                    entityManagerOfB.set(entityManagerOf(context));
                    return countRows(context, id);
                }).get(30, TimeUnit.SECONDS);

                // then
                assertThat(visibleToB).isZero();
                assertThat(entityManagerOfB.get()).isNotSameAs(entityManagerOfA.get());

                mayFinish.countDown();
                a.get(30, TimeUnit.SECONDS);
                assertThat(env.countEventsOfAggregate(id)).isEqualTo(1);
            } finally {
                mayFinish.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Nested
    class AfterTheCommitStep {

        @Test
        void refusesAWriteInAfterCommitInsteadOfSilentlyDroppingIt() {
            // given
            String id = UUID.randomUUID().toString();

            // when: the first write happens after the transaction was committed
            CompletableFuture<Object> unitOfWork = env.unitOfWork(context -> {
                context.runOnAfterCommit(c -> persistAndFlush(c, rowOf(id)));
                return null;
            });

            // then: reported, instead of a successful unit of work whose write is rolled back on close
            assertThatThrownBy(() -> unitOfWork.get(30, TimeUnit.SECONDS))
                    .hasStackTraceContaining("already committed or rolled back");
            assertThat(env.countEventsOfAggregate(id)).isZero();
        }

        @Test
        void refusesAWriteInALaterCommitStepInsteadOfSilentlyDroppingIt() {
            // given
            String id = UUID.randomUUID().toString();

            // when: a commit step registered after the transaction manager's own one writes
            CompletableFuture<Object> unitOfWork = env.unitOfWork(context -> {
                context.runOnCommit(c -> persistAndFlush(c, rowOf(id)));
                return null;
            });

            // then
            assertThatThrownBy(() -> unitOfWork.get(30, TimeUnit.SECONDS))
                    .hasStackTraceContaining("already committed or rolled back");
            assertThat(env.countEventsOfAggregate(id)).isZero();
        }
    }

    @Nested
    class WhenTheDatabaseFails {

        @Test
        void reportsTheFailureAndKeepsThePoolUsableWhenTheConnectionDiesBeforeCommit() throws Exception {
            // given
            String id = UUID.randomUUID().toString();

            // when: the unit of work's connection is terminated between the flush and the commit
            CompletableFuture<Object> unitOfWork = env.unitOfWork(context -> {
                persistAndFlush(context, rowOf(id));
                int pid = backendPid(context);
                context.runOnPrepareCommit(c -> env.query(
                        em -> em.createNativeQuery("SELECT pg_terminate_backend(:pid)")
                                .setParameter("pid", pid)
                                .getSingleResult()));
                return null;
            });

            // then: nothing was stored, and the pool hands a working connection to the next unit of work
            assertThatThrownBy(() -> unitOfWork.get(30, TimeUnit.SECONDS)).isNotNull();
            assertThat(env.countEventsOfAggregate(id)).isZero();
            String next = UUID.randomUUID().toString();
            env.unitOfWork(context -> persistAndFlush(context, rowOf(next))).get(30, TimeUnit.SECONDS);
            assertThat(env.countEventsOfAggregate(next)).isEqualTo(1);
        }

        @Test
        void reportsATransactionThatPostgresAbortedWithoutHibernateNoticing() {
            // given
            String id = UUID.randomUUID().toString();

            // when: a plain JDBC statement fails on the unit of work's connection and the error is swallowed
            CompletableFuture<Object> unitOfWork = env.unitOfWork(context -> {
                persistAndFlush(context, rowOf(id));
                context.getResource(JpaTransactionalExecutorProvider.SUPPLIER_KEY).get().apply(entityManager -> {
                    entityManager.unwrap(Session.class).doWork(connection -> {
                        try (Statement statement = connection.createStatement()) {
                            statement.execute("SELECT 1/0");
                        } catch (SQLException swallowed) {
                            // PostgreSQL has aborted the transaction; Hibernate does not know
                        }
                    });
                    return null;
                }).join();
                return null;
            });

            // then: reported, instead of a commit that PostgreSQL silently turns into a rollback
            assertThatThrownBy(() -> unitOfWork.get(30, TimeUnit.SECONDS))
                    .hasStackTraceContaining("aborted by the database");
            assertThat(env.countEventsOfAggregate(id)).isZero();
        }

        @Test
        void boundsLockWaitsAndIdleTransactionsOnEveryPooledConnection() {
            // when
            Object[] settings = env.query(em -> (Object[]) em.createNativeQuery("""
                    SELECT current_setting('lock_timeout'), current_setting('idle_in_transaction_session_timeout')""")
                                                           .getSingleResult());

            // then: 0 would let a blocked writer, or a transaction left open, wait forever
            assertThat(settings).doesNotContain("0");
        }
    }

    @Nested
    class UnderLoad {

        @Test
        void leavesNothingBehindAfterManyMixedSuccessesAndFailures() throws Exception {
            // given
            String type = "LeakCheck-" + UUID.randomUUID();
            int iterations = 200;

            // when
            for (int i = 0; i < iterations; i++) {
                int iteration = i;
                CompletableFuture<Object> unitOfWork = env.unitOfWork(context -> {
                    persistAndFlush(context, new AggregateEventEntry(
                            UUID.randomUUID().toString(), "test.Row", "1", new byte[]{1}, "{}".getBytes(UTF_8),
                            Instant.now(), type, UUID.randomUUID().toString(), 0L));
                    if (iteration % 2 == 1) {
                        throw new IllegalStateException("odd iterations fail after flush");
                    }
                    return null;
                });
                if (iteration % 2 == 0) {
                    unitOfWork.get(30, TimeUnit.SECONDS);
                } else {
                    assertThatThrownBy(() -> unitOfWork.get(30, TimeUnit.SECONDS)).isNotNull();
                }
            }

            // then: only the even iterations were committed, and no transaction is open (also checked after each test)
            Long committed = env.query(em -> em.createQuery(
                                                       "SELECT count(e) FROM AggregateEventEntry e WHERE e.aggregateType = :type",
                                                       Long.class)
                                               .setParameter("type", type)
                                               .getSingleResult());
            assertThat(committed).isEqualTo(iterations / 2);
        }

        @Test
        void completesMoreConcurrentCommandsThanThereArePooledConnections() throws Exception {
            // A command needs a connection to read events and, in its own unit of work, another one to append them.
            // If the unit of work held its connection while the handler runs, a pool smaller than the number of
            // concurrent commands would deadlock, with every command waiting for a second connection.
            int threads = 16;
            int commandsPerThread = 5;
            try (PostgresAxonEnvironment small = PostgresAxonEnvironment.start(pool -> {
                pool.setMaximumPoolSize(4);
                pool.setConnectionTimeout(5_000);
            })) {
                // given
                Set<String> ssns = new HashSet<>();
                while (ssns.size() < threads * commandsPerThread) {
                    ssns.add(PostgresAxonEnvironment.randomSsn());
                }
                ExecutorService executor = Executors.newFixedThreadPool(threads);
                try {
                    // when
                    List<Future<Object>> results = ssns.stream()
                                                       .map(ssn -> executor.submit(
                                                               () -> small.commands()
                                                                          .sendAndWait(new CreateAccount(ssn, "Load Test"))))
                                                       .toList();
                    for (Future<Object> result : results) {
                        result.get(60, TimeUnit.SECONDS);
                    }

                    // then
                    long stored = ssns.stream().filter(ssn -> small.countEventsOfAggregate(ssn) == 1).count();
                    assertThat(stored).isEqualTo(ssns.size());
                } finally {
                    executor.shutdownNow();
                }
            }
        }
    }

    private static AggregateEventEntry rowOf(String aggregateId) {
        return new AggregateEventEntry(UUID.randomUUID().toString(), "test.Row", "1", new byte[]{1},
                                       "{}".getBytes(UTF_8), Instant.now(), "TestAggregate", aggregateId, 0L);
    }

    private static Object persistAndFlush(ProcessingContext context, AggregateEventEntry row) {
        context.getResource(JpaTransactionalExecutorProvider.SUPPLIER_KEY).get().apply(entityManager -> {
            entityManager.persist(row);
            entityManager.flush();
            return null;
        }).join();
        return null;
    }

    private static EntityManager entityManagerOf(ProcessingContext context) {
        return context.getResource(JpaTransactionalExecutorProvider.SUPPLIER_KEY)
                      .get()
                      .apply(entityManager -> entityManager)
                      .join();
    }

    private static int backendPid(ProcessingContext context) {
        return context.getResource(JpaTransactionalExecutorProvider.SUPPLIER_KEY).get().apply(
                entityManager -> ((Number) entityManager.createNativeQuery("SELECT pg_backend_pid()")
                                                        .getSingleResult()).intValue()).join();
    }

    private static Long countRows(ProcessingContext context, String aggregateId) {
        return context.getResource(JpaTransactionalExecutorProvider.SUPPLIER_KEY).get().apply(
                entityManager -> entityManager.createQuery(
                                                      "SELECT count(e) FROM AggregateEventEntry e WHERE e.aggregateIdentifier = :id",
                                                      Long.class)
                                              .setParameter("id", aggregateId)
                                              .getSingleResult()).join();
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
