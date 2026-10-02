package by.akozel.accountverification.infrastructure;

import java.util.concurrent.CompletableFuture;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.RollbackException;
import org.axonframework.common.function.ThrowingFunction;
import org.axonframework.common.jpa.EntityManagerExecutor;
import org.axonframework.common.tx.TransactionalExecutor;
import org.axonframework.messaging.core.unitofwork.ProcessingLifecycle;
import org.axonframework.messaging.core.unitofwork.transaction.Transaction;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionalExecutorProvider;
import org.axonframework.messaging.core.unitofwork.transaction.jpa.JpaTransactionalExecutorProvider;
import org.hibernate.Session;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.TransactionState;

/**
 * Gives every unit of work its own {@link EntityManager} and database transaction.
 * <p>
 * Axon's own {@code EntityManagerTransactionManager} expects an {@code EntityManagerProvider} that keeps returning
 * the same {@code EntityManager} for the duration of a unit of work (Spring provides one). Plain Java has no such
 * thing, so this manager exposes a unit-of-work scoped {@code EntityManager} to the event storage engine through
 * {@link JpaTransactionalExecutorProvider#SUPPLIER_KEY}, commits it on the commit phase and closes it when the unit
 * of work completes.
 * <p>
 * The {@code EntityManager} and its transaction are created lazily, on first use. Most of a unit of work is spent in
 * the command handler, which reads events through its own short transaction; holding a pooled connection for that
 * whole time would need two connections per command and deadlock a pool that is smaller than the number of concurrent
 * commands. With lazy creation a connection is held only from the moment events are written until the commit.
 * <p>
 * While a unit of work holds its transaction, nothing else on the same thread may open a second connection: waiting
 * for one can exhaust the pool, and writing in a second transaction can wait forever for the first one's locks. This
 * typically happens in a subscribing event handler, which runs inside the publishing unit of work after its events
 * were written; such a request fails immediately. Hand {@link #executorProvider()} to JPA-based Axon components so
 * their own short transactions are checked as well.
 */
public class JpaUnitOfWorkTransactionManager implements TransactionManager {

    private final EntityManagerFactory entityManagerFactory;
    private final JpaTransactionalExecutorProvider executors;
    /** The scope whose transaction is open on this thread, i.e. that holds a pooled connection until it ends. */
    private final ThreadLocal<Scope> openTransaction = new ThreadLocal<>();

    public JpaUnitOfWorkTransactionManager(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
        this.executors = new JpaTransactionalExecutorProvider(entityManagerFactory);
    }

    /**
     * Not supported: the transaction would be bound to an {@code EntityManager} that the caller cannot reach, so
     * nothing could ever be written through it. Axon does not call this method; transactions are attached to the
     * unit of work by {@link #attachToProcessingLifecycle(ProcessingLifecycle)}.
     */
    @Override
    public Transaction startTransaction() {
        throw new UnsupportedOperationException(
                "Transactions are scoped to a unit of work, see attachToProcessingLifecycle(ProcessingLifecycle)");
    }

    @Override
    public void attachToProcessingLifecycle(ProcessingLifecycle lifecycle) {
        lifecycle.runOnPreInvocation(context -> {
            Scope scope = new Scope(context);
            EntityManagerExecutor executor = new EntityManagerExecutor(scope::entityManager);
            context.putResource(JpaTransactionalExecutorProvider.SUPPLIER_KEY, () -> executor);
            context.runOnCommit(c -> scope.commit());
            context.onError((c, phase, error) -> scope.rollback());
            context.doFinally(c -> scope.close());
        });
    }

    @Override
    public boolean requiresSameThreadInvocations() {
        return true; // phases must run one after another on one thread, see openTransaction
    }

    /**
     * Executors for JPA-based Axon components such as the event storage engine: within a unit of work its
     * {@code EntityManager}, outside one a short transaction of its own, refused while this thread holds a transaction.
     */
    public TransactionalExecutorProvider<EntityManager> executorProvider() {
        return context -> context != null ? executors.getTransactionalExecutor(context) : new StandaloneExecutor();
    }

    private void assertNoOpenTransaction() {
        Scope open = openTransaction.get();
        if (open != null && !open.holdsTransaction()) {
            openTransaction.remove(); // its unit of work ended without releasing it
        } else if (open != null) {
            throw new IllegalStateException(
                    "This thread already holds the open transaction of a unit of work that has written and not yet "
                            + "committed; a second connection could exhaust the pool or wait forever for the first "
                            + "transaction's locks. Typical cause: a subscribing event handler that sends a command or "
                            + "reads the event store. Move it to a pooled streaming processor.");
        }
    }

    /** A short transaction of its own, like the one {@link JpaTransactionalExecutorProvider} creates. */
    private final class StandaloneExecutor implements TransactionalExecutor<EntityManager> {

        @Override
        public <R> CompletableFuture<R> apply(ThrowingFunction<EntityManager, R, Exception> function) {
            try {
                assertNoOpenTransaction();
            } catch (IllegalStateException e) {
                return CompletableFuture.failedFuture(e);
            }
            return executors.getTransactionalExecutor(null).apply(function);
        }
    }

    /** The {@code EntityManager} and transaction of one unit of work. Used from a single thread. */
    private final class Scope {

        private final ProcessingLifecycle unitOfWork;
        private EntityManager entityManager;
        private boolean finished; // the commit step ran or the unit of work failed

        private Scope(ProcessingLifecycle unitOfWork) {
            this.unitOfWork = unitOfWork;
        }

        private EntityManager entityManager() {
            if (finished) {
                throw new IllegalStateException(
                        "The unit of work has already committed or rolled back its transaction; "
                                + "anything written now would never be committed");
            }
            if (entityManager == null) {
                assertNoOpenTransaction();
                EntityManager created = entityManagerFactory.createEntityManager();
                try {
                    created.getTransaction().begin();
                } catch (RuntimeException e) {
                    created.close();
                    throw e;
                }
                entityManager = created;
                openTransaction.set(this);
            }
            return entityManager;
        }

        private boolean holdsTransaction() {
            return !finished && !unitOfWork.isCompleted() && entityManager != null && entityManager.isOpen()
                    && entityManager.getTransaction().isActive();
        }

        private void commit() {
            finished = true;
            if (entityManager == null) {
                return; // the unit of work never wrote anything
            }
            try {
                EntityTransaction transaction = entityManager.getTransaction();
                if (transaction.getRollbackOnly() || abortedByDatabase()) {
                    // Committing would silently discard the events while the command is reported as successful.
                    RollbackException failure = new RollbackException(
                            "The transaction was marked rollback-only or aborted by the database; nothing was persisted");
                    try {
                        transaction.rollback();
                    } catch (RuntimeException e) {
                        failure.addSuppressed(e);
                    }
                    throw failure;
                }
                transaction.commit();
            } finally {
                release();
            }
        }

        private void rollback() {
            finished = true;
            try {
                if (entityManager != null && entityManager.isOpen() && entityManager.getTransaction().isActive()) {
                    entityManager.getTransaction().rollback();
                }
            } finally {
                release();
            }
        }

        private void close() {
            finished = true;
            if (entityManager != null && entityManager.isOpen()) {
                try {
                    rollback(); // a transaction that is still active here was never committed
                } finally {
                    entityManager.close();
                }
            }
            release();
        }

        private void release() {
            if (openTransaction.get() == this) {
                openTransaction.remove();
            }
        }

        /**
         * PostgreSQL aborts a transaction on any error, also one that Hibernate never saw (plain JDBC on the same
         * connection); committing it then silently rolls back.
         */
        private boolean abortedByDatabase() {
            try {
                return entityManager.unwrap(Session.class).doReturningWork(
                        connection -> connection.isWrapperFor(BaseConnection.class)
                                && connection.unwrap(BaseConnection.class).getTransactionState()
                                == TransactionState.FAILED);
            } catch (PersistenceException notHibernate) {
                return false;
            }
        }
    }
}
