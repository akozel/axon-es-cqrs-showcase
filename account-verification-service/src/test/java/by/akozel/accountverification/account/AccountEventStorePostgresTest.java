package by.akozel.accountverification.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import by.akozel.accountverification.support.PostgresAxonEnvironment;
import by.akozel.accountverification.support.RequiresPostgresContainer;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.eventstore.AppendEventsTransactionRejectedException;
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.modelling.entity.EntityAlreadyExistsForCreationalCommandHandlerException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies the guarantees the event store gives an account: a per-aggregate sequence number, optimistic locking
 * through the unique (aggregate id, sequence number) index, and correct behaviour under concurrency. Runs against the
 * PostgreSQL test container.
 */
@RequiresPostgresContainer
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class AccountEventStorePostgresTest {

    /** An extra event tagged like every account event, to append more than one event to the same account. */
    record NoteAdded(@EventTag(key = Account.TAG_KEY) String ssn, int number) {}

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
    class SequenceNumbers {

        @Test
        void startAtZeroAndIncreaseByOnePerEventOfTheSameAccount() throws Exception {
            // given
            String ssn = PostgresAxonEnvironment.randomSsn();

            // when
            appendNote(ssn, 1, null).get(30, TimeUnit.SECONDS);
            appendNote(ssn, 2, null).get(30, TimeUnit.SECONDS);
            appendNote(ssn, 3, null).get(30, TimeUnit.SECONDS);

            // then
            assertThat(env.sequenceNumbersOfAggregate(ssn)).containsExactly(0L, 1L, 2L);
        }

        @Test
        void areIndependentPerAccount() throws Exception {
            // given
            String first = PostgresAxonEnvironment.randomSsn();
            String second = PostgresAxonEnvironment.randomSsn();

            // when
            appendNote(first, 1, null).get(30, TimeUnit.SECONDS);
            appendNote(first, 2, null).get(30, TimeUnit.SECONDS);
            appendNote(second, 1, null).get(30, TimeUnit.SECONDS);

            // then
            assertThat(env.sequenceNumbersOfAggregate(first)).containsExactly(0L, 1L);
            assertThat(env.sequenceNumbersOfAggregate(second)).containsExactly(0L);
        }
    }

    @Nested
    class OptimisticLocking {

        @Test
        void rejectsOneOfTwoConcurrentFirstEventsOfTheSameAccount() {
            // given: nothing is stored for the account yet
            String ssn = PostgresAxonEnvironment.randomSsn();

            // when: both writers read the (empty) account before either one writes
            List<Throwable> outcomes = raceTwoAppends(ssn);

            // then: exactly one wins; the loser is told it lost the race
            assertExactlyOneRejectedByOptimisticLock(outcomes);
            assertThat(env.sequenceNumbersOfAggregate(ssn)).containsExactly(0L);
        }

        @Test
        void rejectsOneOfTwoConcurrentNextEventsOfAnExistingAccount() throws Exception {
            // given: the account already has event 0
            String ssn = PostgresAxonEnvironment.randomSsn();
            appendNote(ssn, 0, null).get(30, TimeUnit.SECONDS);

            // when: both writers read it at event 0 and both want to append event 1
            List<Throwable> outcomes = raceTwoAppends(ssn);

            // then
            assertExactlyOneRejectedByOptimisticLock(outcomes);
            assertThat(env.sequenceNumbersOfAggregate(ssn)).containsExactly(0L, 1L);
        }

        @Test
        void letsTheLoserSucceedWhenItRetriesAfterRereadingTheAccount() throws Exception {
            // given: a lost race on an existing account
            String ssn = PostgresAxonEnvironment.randomSsn();
            appendNote(ssn, 0, null).get(30, TimeUnit.SECONDS);
            assertExactlyOneRejectedByOptimisticLock(raceTwoAppends(ssn));

            // when: the loser retries, this time reading the winner's event first
            appendNote(ssn, 99, null).get(30, TimeUnit.SECONDS);

            // then: the retry is stored after the winner's event; nothing is lost or duplicated
            assertThat(env.sequenceNumbersOfAggregate(ssn)).containsExactly(0L, 1L, 2L);
        }

        @Test
        void doesNotRejectConcurrentEventsOfDifferentAccounts() throws Exception {
            // given
            String first = PostgresAxonEnvironment.randomSsn();
            String second = PostgresAxonEnvironment.randomSsn();
            CyclicBarrier bothHaveRead = new CyclicBarrier(2);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                // when
                Future<?> a = executor.submit(() -> appendNote(first, 1, bothHaveRead).get(30, TimeUnit.SECONDS));
                Future<?> b = executor.submit(() -> appendNote(second, 1, bothHaveRead).get(30, TimeUnit.SECONDS));
                a.get(60, TimeUnit.SECONDS);
                b.get(60, TimeUnit.SECONDS);

                // then
                assertThat(env.sequenceNumbersOfAggregate(first)).containsExactly(0L);
                assertThat(env.sequenceNumbersOfAggregate(second)).containsExactly(0L);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Nested
    class ConcurrentCommands {

        @Test
        void createsManyDifferentAccountsInParallel() throws Exception {
            // given
            Set<String> ssns = new HashSet<>();
            while (ssns.size() < 100) {
                ssns.add(PostgresAxonEnvironment.randomSsn());
            }
            ExecutorService executor = Executors.newFixedThreadPool(16);
            try {
                // when
                List<Future<Object>> results = ssns.stream()
                                                   .map(ssn -> executor.submit(() -> env.commands().sendAndWait(
                                                           new CreateAccount(ssn, "Parallel"))))
                                                   .toList();
                for (Future<Object> result : results) {
                    result.get(60, TimeUnit.SECONDS);
                }

                // then: every account has exactly its creation event, with sequence number 0
                for (String ssn : ssns) {
                    assertThat(env.sequenceNumbersOfAggregate(ssn)).as(ssn).containsExactly(0L);
                }
            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        void createsTheSameAccountExactlyOnceWhenManyCommandsRaceForIt() throws Exception {
            // given
            String ssn = PostgresAxonEnvironment.randomSsn();
            int contenders = 20;
            CyclicBarrier startTogether = new CyclicBarrier(contenders);
            ExecutorService executor = Executors.newFixedThreadPool(contenders);
            try {
                // when
                List<Future<Throwable>> results = Stream.generate(() -> executor.submit((Callable<Throwable>) () -> {
                    startTogether.await(30, TimeUnit.SECONDS);
                    try {
                        env.commands().sendAndWait(new CreateAccount(ssn, "Contender"));
                        return null;
                    } catch (Throwable failure) {
                        return failure;
                    }
                })).limit(contenders).toList();
                List<Throwable> failures = new ArrayList<>();
                int successes = 0;
                for (Future<Throwable> result : results) {
                    Throwable failure = result.get(60, TimeUnit.SECONDS);
                    if (failure == null) {
                        successes++;
                    } else {
                        failures.add(failure);
                    }
                }

                // then: one account was created; every other command was rejected for a known reason
                assertThat(successes).isEqualTo(1);
                assertThat(failures).hasSize(contenders - 1)
                                    .allSatisfy(failure -> assertThat(causesOf(failure)).anyMatch(
                                            cause -> cause instanceof EntityAlreadyExistsForCreationalCommandHandlerException
                                                    || cause instanceof AppendEventsTransactionRejectedException));
                assertThat(env.sequenceNumbersOfAggregate(ssn)).containsExactly(0L);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    /**
     * Reads the account's events and appends one more in the same unit of work, like a command handler would. If
     * {@code bothHaveRead} is given, the append waits until every party has read, which makes two callers decide on
     * the same state.
     */
    private CompletableFuture<Object> appendNote(String ssn, int number, CyclicBarrier bothHaveRead) {
        return env.unitOfWork(context -> {
            readAccount(context, ssn);
            if (bothHaveRead != null) {
                try {
                    bothHaveRead.await(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("The other writer did not arrive", e);
                }
            }
            EventAppender.forContext(context).append(new NoteAdded(ssn, number));
            return null;
        });
    }

    private static void readAccount(ProcessingContext context, String ssn) {
        env.eventStore()
           .transaction(context)
           .source(SourcingCondition.conditionFor(EventCriteria.havingTags(Account.TAG_KEY, ssn)))
           .reduce(0, (count, entry) -> count + 1)
           .join();
    }

    /** Runs two appends to the same account that both read before either writes; returns null (won) or the failure. */
    private List<Throwable> raceTwoAppends(String ssn) {
        CyclicBarrier bothHaveRead = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> first = executor.submit(() -> outcomeOf(appendNote(ssn, 1, bothHaveRead)));
            Future<Throwable> second = executor.submit(() -> outcomeOf(appendNote(ssn, 2, bothHaveRead)));
            return Arrays.asList(first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS));
        } catch (Exception e) {
            throw new AssertionError("The race did not finish", e);
        } finally {
            executor.shutdownNow();
        }
    }

    private static Throwable outcomeOf(CompletableFuture<?> unitOfWork) throws Exception {
        try {
            unitOfWork.get(30, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }

    private static void assertExactlyOneRejectedByOptimisticLock(List<Throwable> outcomes) {
        List<Throwable> losers = outcomes.stream().filter(outcome -> outcome != null).toList();
        assertThat(outcomes).as("one writer wins, the other loses").hasSize(2);
        assertThat(losers).hasSize(1);
        assertThat(causesOf(losers.getFirst()))
                .as("the loser is rejected as a concurrent modification")
                .anyMatch(AppendEventsTransactionRejectedException.class::isInstance);
    }

    private static List<Throwable> causesOf(Throwable failure) {
        ArrayList<Throwable> chain = new ArrayList<>();
        for (Throwable current = failure; current != null && !chain.contains(current); current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }
}
