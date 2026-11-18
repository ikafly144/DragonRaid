package net.sabafly.dragonraid.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.sqlite.SQLiteConfig;

/**
 * Shared SQLite connection pool, WAL configuration, single-writer transaction serialization,
 * and graceful async drain lifecycle used across DragonRaid repositories.
 */
public final class SqliteDatabase implements AutoCloseable {

    private static final int BUSY_TIMEOUT_MS = 5000;
    private static final Duration DEFAULT_CLOSE_DRAIN_TIMEOUT = Duration.ofSeconds(5);

    private final HikariDataSource dataSource;
    private final Executor asyncExecutor;
    private final long closeDrainTimeoutNanos;
    private final ReentrantLock writeLock = new ReentrantLock(true);
    private final AtomicBoolean closing = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Set<PendingTask> pendingTasks = ConcurrentHashMap.newKeySet();

    private record PendingTask(CompletableFuture<Void> internalDone, CompletableFuture<?> publicFuture) {
    }

    public static final class TransactionContext {
        private boolean rollbackOnly;
        private final List<Runnable> afterCommitHooks = new ArrayList<>();

        public void markRollbackOnly() {
            this.rollbackOnly = true;
        }

        /**
         * Registers a hook to execute after {@link Connection#commit()} succeeds while the
         * single-writer lock is still held. Not invoked if the transaction rolls back or fails.
         */
        public void onCommit(Runnable hook) {
            Objects.requireNonNull(hook, "hook");
            afterCommitHooks.add(hook);
        }

        private void runAfterCommit() {
            for (Runnable hook : afterCommitHooks) {
                hook.run();
            }
        }
    }

    @FunctionalInterface
    public interface SqlFunction<I, O> {
        O apply(I input) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlTxFunction<O> {
        O apply(Connection connection, TransactionContext tx) throws SQLException;
    }

    public SqliteDatabase(Path databasePath, int poolSize, Executor asyncExecutor) {
        this(databasePath, poolSize, asyncExecutor, DEFAULT_CLOSE_DRAIN_TIMEOUT);
    }

    SqliteDatabase(Path databasePath, int poolSize, Executor asyncExecutor, Duration closeDrainTimeout) {
        Objects.requireNonNull(databasePath, "databasePath must not be null");
        Objects.requireNonNull(asyncExecutor, "asyncExecutor must not be null");
        Objects.requireNonNull(closeDrainTimeout, "closeDrainTimeout must not be null");
        if (poolSize < 1) {
            throw new IllegalArgumentException("poolSize must be >= 1");
        }
        if (closeDrainTimeout.isNegative()) {
            throw new IllegalArgumentException("closeDrainTimeout must not be negative");
        }
        this.asyncExecutor = asyncExecutor;
        this.closeDrainTimeoutNanos = closeDrainTimeout.toNanos();

        Path normalizedPath = databasePath.toAbsolutePath().normalize();
        createParentDirectories(normalizedPath);

        SQLiteConfig sqliteConfig = new SQLiteConfig();
        sqliteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqliteConfig.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        sqliteConfig.enforceForeignKeys(true);
        sqliteConfig.setBusyTimeout(BUSY_TIMEOUT_MS);
        sqliteConfig.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPoolName("DragonRaid-SQLite");
        hikariConfig.setDriverClassName("org.sqlite.JDBC");
        hikariConfig.setJdbcUrl("jdbc:sqlite:" + normalizedPath);
        hikariConfig.setMaximumPoolSize(poolSize);
        hikariConfig.setMinimumIdle(1);
        hikariConfig.setConnectionTimeout(10_000L);
        hikariConfig.setDataSourceProperties(sqliteConfig.toProperties());

        this.dataSource = new HikariDataSource(hikariConfig);
    }

    public boolean isClosed() {
        return closed.get();
    }

    public void ensureAccepting() {
        if (closing.get() || closed.get()) {
            throw new IllegalStateException("SqliteDatabase is already closed");
        }
    }

    public <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        ensureAccepting();
        CompletableFuture<Void> internalDone = new CompletableFuture<>();
        CompletableFuture<T> publicFuture = new CompletableFuture<>();
        PendingTask task = new PendingTask(internalDone, publicFuture);
        pendingTasks.add(task);
        if (closing.get()) {
            pendingTasks.remove(task);
            internalDone.complete(null);
            throw new IllegalStateException("SqliteDatabase is already closed");
        }
        internalDone.whenComplete((ignored, error) -> pendingTasks.remove(task));
        try {
            asyncExecutor.execute(() -> {
                if (internalDone.isDone() || publicFuture.isDone()) {
                    internalDone.complete(null);
                    return;
                }
                try {
                    T result = supplier.get();
                    publicFuture.complete(result);
                } catch (Throwable ex) {
                    publicFuture.completeExceptionally(ex);
                } finally {
                    internalDone.complete(null);
                }
            });
        } catch (Throwable ex) {
            pendingTasks.remove(task);
            publicFuture.completeExceptionally(ex);
            internalDone.complete(null);
            throw ex;
        }
        return publicFuture;
    }

    public CompletableFuture<Void> runAsync(Runnable runnable) {
        return supplyAsync(() -> {
            runnable.run();
            return null;
        });
    }

    public <T> T withReadConnection(SqlFunction<Connection, T> action) {
        ensureOpen();
        try (Connection conn = dataSource.getConnection()) {
            return action.apply(conn);
        } catch (SQLException ex) {
            throw new IllegalStateException("SQLite read operation failed", ex);
        }
    }

    public <T> T withWriteTransaction(SqlTxFunction<T> action) {
        ensureOpen();
        writeLock.lock();
        try (Connection conn = dataSource.getConnection()) {
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            TransactionContext tx = new TransactionContext();
            try {
                T result = action.apply(conn, tx);
                if (tx.rollbackOnly) {
                    conn.rollback();
                } else {
                    conn.commit();
                    tx.runAfterCommit();
                }
                return result;
            } catch (Throwable ex) {
                try {
                    conn.rollback();
                } catch (SQLException suppressed) {
                    ex.addSuppressed(suppressed);
                }
                if (ex instanceof SQLException sqlEx) {
                    throw new IllegalStateException("SQLite write transaction failed", sqlEx);
                }
                if (ex instanceof RuntimeException runtimeEx) {
                    throw runtimeEx;
                }
                throw new IllegalStateException("SQLite write transaction failed", ex);
            } finally {
                try {
                    conn.setAutoCommit(originalAutoCommit);
                } catch (SQLException ignored) {
                    // Connection will be closed/reset by HikariCP.
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to obtain SQLite write connection", ex);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        List<PendingTask> timedOut;
        try {
            drainPendingTasks();
        } finally {
            closed.set(true);
            timedOut = List.copyOf(pendingTasks);
            for (PendingTask leftover : timedOut) {
                pendingTasks.remove(leftover);
                leftover.internalDone().complete(null);
            }
            dataSource.close();
        }
        for (PendingTask leftover : timedOut) {
            ForkJoinPool.commonPool().execute(() -> leftover.publicFuture().completeExceptionally(
                new CancellationException("SqliteDatabase closed before completion")
            ));
        }
    }

    private void drainPendingTasks() {
        long deadline = System.nanoTime() + closeDrainTimeoutNanos;
        while (!pendingTasks.isEmpty()) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0L) {
                break;
            }
            CompletableFuture<?>[] snapshot = pendingTasks.stream()
                .map(PendingTask::internalDone)
                .toArray(CompletableFuture[]::new);
            if (snapshot.length == 0) {
                break;
            }
            try {
                CompletableFuture.allOf(snapshot).get(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            } catch (TimeoutException ex) {
                break;
            } catch (ExecutionException ex) {
                // Individual task failures are propagated on each public future.
            }
        }
    }

    public void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("SqliteDatabase is already closed");
        }
    }

    private static void createParentDirectories(Path databasePath) {
        Path parent = databasePath.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to create database directory: " + parent, ex);
            }
        }
    }
}
