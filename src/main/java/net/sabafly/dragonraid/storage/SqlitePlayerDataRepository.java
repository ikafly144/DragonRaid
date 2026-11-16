package net.sabafly.dragonraid.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.sabafly.dragonraid.storage.PointTransactionResult.FailureReason;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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

/**
 * SQLite-backed {@link PlayerDataRepository} using HikariCP, WAL mode, parameterized SQL,
 * and an injected async {@link Executor} for Folia compatibility.
 */
public final class SqlitePlayerDataRepository implements PlayerDataRepository {

    private static final int DEFAULT_POOL_SIZE = 4;
    private static final int BUSY_TIMEOUT_MS = 5000;
    private static final Duration DEFAULT_CLOSE_DRAIN_TIMEOUT = Duration.ofSeconds(5);

    private final HikariDataSource dataSource;
    private final Executor asyncExecutor;
    private final long closeDrainTimeoutNanos;
    // SQLite permits one concurrent writer per database; serializing write transactions in-JVM
    // eliminates SQLITE_BUSY contention while reads proceed concurrently under WAL mode.
    private final ReentrantLock writeLock = new ReentrantLock(true);
    private final AtomicBoolean closing = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Set<PendingTask> pendingTasks = ConcurrentHashMap.newKeySet();

    private record PendingTask(CompletableFuture<Void> internalDone, CompletableFuture<?> publicFuture) {
    }

    public SqlitePlayerDataRepository(Path databasePath, Executor asyncExecutor) {
        this(databasePath, DEFAULT_POOL_SIZE, asyncExecutor, DEFAULT_CLOSE_DRAIN_TIMEOUT);
    }

    public SqlitePlayerDataRepository(Path databasePath, int poolSize, Executor asyncExecutor) {
        this(databasePath, poolSize, asyncExecutor, DEFAULT_CLOSE_DRAIN_TIMEOUT);
    }

    SqlitePlayerDataRepository(Path databasePath, int poolSize, Executor asyncExecutor, Duration closeDrainTimeout) {
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
        try {
            initializeSchema();
        } catch (RuntimeException ex) {
            this.dataSource.close();
            throw ex;
        }
    }

    @Override
    public CompletableFuture<Optional<PlayerRaidStats>> findByUuid(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid must not be null");
        return supplyAsync(() -> withReadConnection(conn -> selectByUuid(conn, uuid)));
    }

    @Override
    public CompletableFuture<Optional<PlayerRaidStats>> findByName(String name) {
        ensureAccepting();
        String normalized = normalizeName(name);
        if (normalized == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return supplyAsync(() -> withReadConnection(conn -> selectByName(conn, normalized)));
    }

    @Override
    public CompletableFuture<PlayerRaidStats> getOrCreate(UUID uuid, String lastKnownName) {
        Objects.requireNonNull(uuid, "uuid must not be null");
        String normalizedName = normalizeName(lastKnownName);
        return supplyAsync(() -> withWriteTransaction((conn, tx) -> {
            ensurePlayerExists(conn, uuid, normalizedName);
            return selectByUuid(conn, uuid)
                .orElseThrow(() -> new IllegalStateException("Inserted player row missing for " + uuid));
        }));
    }

    @Override
    public CompletableFuture<Void> updateLastKnownName(UUID uuid, String lastKnownName) {
        Objects.requireNonNull(uuid, "uuid must not be null");
        String normalizedName = normalizeName(lastKnownName);
        return runAsync(() -> withWriteTransaction((conn, tx) -> {
            ensurePlayerExists(conn, uuid, normalizedName);
            return null;
        }));
    }

    @Override
    public CompletableFuture<PointTransactionResult> addPoints(UUID uuid, String lastKnownName, long amount) {
        Objects.requireNonNull(uuid, "uuid must not be null");
        String normalizedName = normalizeName(lastKnownName);
        return supplyAsync(() -> withWriteTransaction((conn, tx) -> {
            if (amount <= 0) {
                long current = findVirtualPoints(conn, uuid).orElse(0L);
                return PointTransactionResult.failed(FailureReason.INVALID_AMOUNT, current);
            }
            ensurePlayerExists(conn, uuid, normalizedName);
            long current = queryVirtualPoints(conn, uuid);
            if (amount > Long.MAX_VALUE - current) {
                return PointTransactionResult.failed(FailureReason.BALANCE_OVERFLOW, current);
            }
            long totalEarned = queryTotalEarnedPoints(conn, uuid);
            if (amount > Long.MAX_VALUE - totalEarned) {
                return PointTransactionResult.failed(FailureReason.BALANCE_OVERFLOW, current);
            }
            String sql = """
                UPDATE player_stats
                SET virtual_points = virtual_points + ?,
                    total_earned_points = total_earned_points + ?,
                    updated_at = unixepoch()
                WHERE uuid = ?
                """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, amount);
                ps.setLong(2, amount);
                ps.setString(3, uuid.toString());
                ps.executeUpdate();
            }
            return PointTransactionResult.succeeded(current, current + amount);
        }));
    }

    @Override
    public CompletableFuture<PointTransactionResult> trySpendPoints(UUID uuid, long amount) {
        Objects.requireNonNull(uuid, "uuid must not be null");
        return supplyAsync(() -> withWriteTransaction((conn, tx) -> {
            Optional<Long> existing = findVirtualPoints(conn, uuid);
            if (existing.isEmpty()) {
                return PointTransactionResult.failed(FailureReason.PLAYER_NOT_FOUND, 0L);
            }
            long current = existing.get();
            if (amount <= 0) {
                return PointTransactionResult.failed(FailureReason.INVALID_AMOUNT, current);
            }
            String sql = """
                UPDATE player_stats
                SET virtual_points = virtual_points - ?,
                    updated_at = unixepoch()
                WHERE uuid = ? AND virtual_points >= ?
                """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, amount);
                ps.setString(2, uuid.toString());
                ps.setLong(3, amount);
                if (ps.executeUpdate() == 0) {
                    return PointTransactionResult.failed(FailureReason.INSUFFICIENT_FUNDS, current);
                }
            }
            return PointTransactionResult.succeeded(current, current - amount);
        }));
    }

    @Override
    public CompletableFuture<PointTransactionResult> setPoints(UUID uuid, String lastKnownName, long newBalance) {
        Objects.requireNonNull(uuid, "uuid must not be null");
        String normalizedName = normalizeName(lastKnownName);
        return supplyAsync(() -> withWriteTransaction((conn, tx) -> {
            if (newBalance < 0) {
                long current = findVirtualPoints(conn, uuid).orElse(0L);
                return PointTransactionResult.failed(FailureReason.INVALID_AMOUNT, current);
            }
            ensurePlayerExists(conn, uuid, normalizedName);
            long current = queryVirtualPoints(conn, uuid);
            String sql = """
                UPDATE player_stats
                SET virtual_points = ?,
                    updated_at = unixepoch()
                WHERE uuid = ?
                """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, newBalance);
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            }
            return PointTransactionResult.succeeded(current, newBalance);
        }));
    }

    @Override
    public CompletableFuture<PlayerRaidStats> recordClear(
        UUID uuid,
        String lastKnownName,
        Difficulty difficulty,
        long clearTimeMs
    ) {
        Objects.requireNonNull(uuid, "uuid must not be null");
        Objects.requireNonNull(difficulty, "difficulty must not be null");
        if (clearTimeMs < 0) {
            throw new IllegalArgumentException("clearTimeMs must be >= 0");
        }
        String normalizedName = normalizeName(lastKnownName);
        return supplyAsync(() -> withWriteTransaction((conn, tx) -> {
            ensurePlayerExists(conn, uuid, normalizedName);
            String clearsColumn = difficulty == Difficulty.NORMAL ? "normal_clears" : "hard_clears";
            String bestTimeColumn = difficulty == Difficulty.NORMAL ? "best_normal_time_ms" : "best_hard_time_ms";
            String sql = """
                UPDATE player_stats
                SET %s = %s + 1,
                    %s = CASE
                        WHEN %s IS NULL OR ? < %s THEN ?
                        ELSE %s
                    END,
                    updated_at = unixepoch()
                WHERE uuid = ?
                """.formatted(clearsColumn, clearsColumn, bestTimeColumn, bestTimeColumn, bestTimeColumn, bestTimeColumn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, clearTimeMs);
                ps.setLong(2, clearTimeMs);
                ps.setString(3, uuid.toString());
                ps.executeUpdate();
            }
            return selectByUuid(conn, uuid)
                .orElseThrow(() -> new IllegalStateException("Player row missing after clear update: " + uuid));
        }));
    }

    @Override
    public CompletableFuture<PlayerRaidStats> incrementDeaths(UUID uuid, String lastKnownName) {
        return incrementCounter(uuid, lastKnownName, CounterColumn.DEATHS);
    }

    @Override
    public CompletableFuture<PlayerRaidStats> incrementRevives(UUID uuid, String lastKnownName) {
        return incrementCounter(uuid, lastKnownName, CounterColumn.REVIVES);
    }

    @Override
    public CompletableFuture<PointTransactionResult> addCurrency(
        UUID uuid,
        String lastKnownName,
        String currencyId,
        long amount
    ) {
        Objects.requireNonNull(uuid, "uuid must not be null");
        String validCurrency = requireCurrencyId(currencyId);
        String normalizedName = normalizeName(lastKnownName);
        return supplyAsync(() -> withWriteTransaction((conn, tx) -> {
            if (amount <= 0) {
                long current = queryCurrencyBalance(conn, uuid, validCurrency);
                return PointTransactionResult.failed(FailureReason.INVALID_AMOUNT, current);
            }
            ensurePlayerExists(conn, uuid, normalizedName);
            long current = queryCurrencyBalance(conn, uuid, validCurrency);
            if (amount > Long.MAX_VALUE - current) {
                return PointTransactionResult.failed(FailureReason.BALANCE_OVERFLOW, current);
            }
            String sql = """
                INSERT INTO player_currencies (uuid, currency_id, balance, total_earned, updated_at)
                VALUES (?, ?, ?, ?, unixepoch())
                ON CONFLICT(uuid, currency_id) DO UPDATE SET
                    balance = player_currencies.balance + excluded.balance,
                    total_earned = player_currencies.total_earned + excluded.total_earned,
                    updated_at = unixepoch()
                """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, validCurrency);
                ps.setLong(3, amount);
                ps.setLong(4, amount);
                ps.executeUpdate();
            }
            return PointTransactionResult.succeeded(current, current + amount);
        }));
    }

    @Override
    public CompletableFuture<PointTransactionResult> trySpendCurrency(UUID uuid, String currencyId, long amount) {
        Objects.requireNonNull(uuid, "uuid must not be null");
        String validCurrency = requireCurrencyId(currencyId);
        return supplyAsync(() -> withWriteTransaction((conn, tx) -> {
            Optional<Long> existingPlayer = findVirtualPoints(conn, uuid);
            if (existingPlayer.isEmpty()) {
                return PointTransactionResult.failed(FailureReason.PLAYER_NOT_FOUND, 0L);
            }
            long current = queryCurrencyBalance(conn, uuid, validCurrency);
            if (amount <= 0) {
                return PointTransactionResult.failed(FailureReason.INVALID_AMOUNT, current);
            }
            if (!deductSingleCurrency(conn, uuid, validCurrency, amount)) {
                return PointTransactionResult.failed(FailureReason.INSUFFICIENT_FUNDS, current);
            }
            return PointTransactionResult.succeeded(current, current - amount);
        }));
    }

    @Override
    public CompletableFuture<Boolean> trySpendCurrencies(UUID uuid, Map<String, Long> costs) {
        ensureOpen();
        Objects.requireNonNull(uuid, "uuid must not be null");
        Objects.requireNonNull(costs, "costs must not be null");
        Map<String, Long> validatedCosts = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : costs.entrySet()) {
            String currencyId = requireCurrencyId(entry.getKey());
            Long amount = entry.getValue();
            if (amount == null || amount <= 0) {
                throw new IllegalArgumentException("Currency cost must be > 0 for " + currencyId);
            }
            validatedCosts.merge(currencyId, amount, Math::addExact);
        }
        if (validatedCosts.isEmpty()) {
            return CompletableFuture.completedFuture(Boolean.TRUE);
        }
        return supplyAsync(() -> withWriteTransaction((conn, tx) -> {
            for (Map.Entry<String, Long> entry : validatedCosts.entrySet()) {
                if (!deductSingleCurrency(conn, uuid, entry.getKey(), entry.getValue())) {
                    tx.markRollbackOnly();
                    return Boolean.FALSE;
                }
            }
            return Boolean.TRUE;
        }));
    }

    @Override
    public CompletableFuture<PointTransactionResult> setCurrency(
        UUID uuid,
        String lastKnownName,
        String currencyId,
        long newBalance
    ) {
        Objects.requireNonNull(uuid, "uuid must not be null");
        String validCurrency = requireCurrencyId(currencyId);
        String normalizedName = normalizeName(lastKnownName);
        return supplyAsync(() -> withWriteTransaction((conn, tx) -> {
            if (newBalance < 0) {
                long current = queryCurrencyBalance(conn, uuid, validCurrency);
                return PointTransactionResult.failed(FailureReason.INVALID_AMOUNT, current);
            }
            ensurePlayerExists(conn, uuid, normalizedName);
            long current = queryCurrencyBalance(conn, uuid, validCurrency);
            String sql = """
                INSERT INTO player_currencies (uuid, currency_id, balance, total_earned, updated_at)
                VALUES (?, ?, ?, 0, unixepoch())
                ON CONFLICT(uuid, currency_id) DO UPDATE SET
                    balance = excluded.balance,
                    updated_at = unixepoch()
                """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, validCurrency);
                ps.setLong(3, newBalance);
                ps.executeUpdate();
            }
            return PointTransactionResult.succeeded(current, newBalance);
        }));
    }

    @Override
    public CompletableFuture<Long> getCurrencyBalance(UUID uuid, String currencyId) {
        Objects.requireNonNull(uuid, "uuid must not be null");
        String validCurrency = requireCurrencyId(currencyId);
        return supplyAsync(() -> withReadConnection(conn -> queryCurrencyBalance(conn, uuid, validCurrency)));
    }

    @Override
    public CompletableFuture<Map<String, Long>> getCurrencyBalances(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid must not be null");
        return supplyAsync(() -> withReadConnection(conn -> {
            String sql = "SELECT currency_id, balance FROM player_currencies WHERE uuid = ? ORDER BY currency_id";
            Map<String, Long> balances = new LinkedHashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        balances.put(rs.getString("currency_id"), rs.getLong("balance"));
                    }
                }
            }
            return Collections.unmodifiableMap(balances);
        }));
    }

    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        List<PendingTask> timedOut = List.of();
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
                new CancellationException("SqlitePlayerDataRepository closed before completion")
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

    private void initializeSchema() {
        withWriteTransaction((conn, tx) -> {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS player_stats (
                        uuid TEXT PRIMARY KEY,
                        last_known_name TEXT,
                        virtual_points INTEGER NOT NULL DEFAULT 0 CHECK (virtual_points >= 0),
                        total_earned_points INTEGER NOT NULL DEFAULT 0 CHECK (total_earned_points >= 0),
                        normal_clears INTEGER NOT NULL DEFAULT 0 CHECK (normal_clears >= 0),
                        hard_clears INTEGER NOT NULL DEFAULT 0 CHECK (hard_clears >= 0),
                        best_normal_time_ms INTEGER CHECK (best_normal_time_ms IS NULL OR best_normal_time_ms >= 0),
                        best_hard_time_ms INTEGER CHECK (best_hard_time_ms IS NULL OR best_hard_time_ms >= 0),
                        total_deaths INTEGER NOT NULL DEFAULT 0 CHECK (total_deaths >= 0),
                        total_revives INTEGER NOT NULL DEFAULT 0 CHECK (total_revives >= 0),
                        updated_at INTEGER NOT NULL DEFAULT (unixepoch())
                    )
                    """);
                stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_player_stats_name
                        ON player_stats (last_known_name COLLATE NOCASE)
                    """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS player_currencies (
                        uuid TEXT NOT NULL,
                        currency_id TEXT NOT NULL,
                        balance INTEGER NOT NULL DEFAULT 0 CHECK (balance >= 0),
                        total_earned INTEGER NOT NULL DEFAULT 0 CHECK (total_earned >= 0),
                        updated_at INTEGER NOT NULL DEFAULT (unixepoch()),
                        PRIMARY KEY (uuid, currency_id),
                        FOREIGN KEY (uuid) REFERENCES player_stats (uuid) ON DELETE CASCADE
                    )
                    """);
            }
            return null;
        });
    }

    private CompletableFuture<PlayerRaidStats> incrementCounter(
        UUID uuid,
        String lastKnownName,
        CounterColumn column
    ) {
        Objects.requireNonNull(uuid, "uuid must not be null");
        String normalizedName = normalizeName(lastKnownName);
        return supplyAsync(() -> withWriteTransaction((conn, tx) -> {
            ensurePlayerExists(conn, uuid, normalizedName);
            String sql = """
                UPDATE player_stats
                SET %s = %s + 1,
                    updated_at = unixepoch()
                WHERE uuid = ?
                """.formatted(column.columnName, column.columnName);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
            return selectByUuid(conn, uuid)
                .orElseThrow(() -> new IllegalStateException("Player row missing after counter update: " + uuid));
        }));
    }

    private void ensurePlayerExists(Connection conn, UUID uuid, String normalizedName) throws SQLException {
        String sql = """
            INSERT INTO player_stats (uuid, last_known_name, updated_at)
            VALUES (?, ?, unixepoch())
            ON CONFLICT(uuid) DO UPDATE SET
                last_known_name = COALESCE(excluded.last_known_name, player_stats.last_known_name),
                updated_at = unixepoch()
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, normalizedName);
            ps.executeUpdate();
        }
    }

    private Optional<PlayerRaidStats> selectByUuid(Connection conn, UUID uuid) throws SQLException {
        String sql = """
            SELECT uuid, last_known_name, virtual_points, total_earned_points,
                   normal_clears, hard_clears, best_normal_time_ms, best_hard_time_ms,
                   total_deaths, total_revives
            FROM player_stats
            WHERE uuid = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapStatsRow(rs)) : Optional.empty();
            }
        }
    }

    private Optional<PlayerRaidStats> selectByName(Connection conn, String name) throws SQLException {
        String sql = """
            SELECT uuid, last_known_name, virtual_points, total_earned_points,
                   normal_clears, hard_clears, best_normal_time_ms, best_hard_time_ms,
                   total_deaths, total_revives
            FROM player_stats
            WHERE last_known_name = ? COLLATE NOCASE
            ORDER BY updated_at DESC
            LIMIT 1
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapStatsRow(rs)) : Optional.empty();
            }
        }
    }

    private PlayerRaidStats mapStatsRow(ResultSet rs) throws SQLException {
        return new PlayerRaidStats(
            UUID.fromString(rs.getString("uuid")),
            rs.getString("last_known_name"),
            rs.getLong("virtual_points"),
            rs.getLong("total_earned_points"),
            rs.getInt("normal_clears"),
            rs.getInt("hard_clears"),
            getNullableLong(rs, "best_normal_time_ms"),
            getNullableLong(rs, "best_hard_time_ms"),
            rs.getInt("total_deaths"),
            rs.getInt("total_revives")
        );
    }

    private Long getNullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Optional<Long> findVirtualPoints(Connection conn, UUID uuid) throws SQLException {
        String sql = "SELECT virtual_points FROM player_stats WHERE uuid = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong("virtual_points")) : Optional.empty();
            }
        }
    }

    private long queryVirtualPoints(Connection conn, UUID uuid) throws SQLException {
        return findVirtualPoints(conn, uuid)
            .orElseThrow(() -> new IllegalStateException("Player row missing for " + uuid));
    }

    private long queryTotalEarnedPoints(Connection conn, UUID uuid) throws SQLException {
        String sql = "SELECT total_earned_points FROM player_stats WHERE uuid = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("Player row missing for " + uuid);
                }
                return rs.getLong("total_earned_points");
            }
        }
    }

    private long queryCurrencyBalance(Connection conn, UUID uuid, String currencyId) throws SQLException {
        String sql = "SELECT balance FROM player_currencies WHERE uuid = ? AND currency_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, currencyId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("balance") : 0L;
            }
        }
    }

    private boolean deductSingleCurrency(Connection conn, UUID uuid, String currencyId, long amount) throws SQLException {
        String sql = """
            UPDATE player_currencies
            SET balance = balance - ?,
                updated_at = unixepoch()
            WHERE uuid = ? AND currency_id = ? AND balance >= ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, amount);
            ps.setString(2, uuid.toString());
            ps.setString(3, currencyId);
            ps.setLong(4, amount);
            return ps.executeUpdate() > 0;
        }
    }

    private <T> CompletableFuture<T> supplyAsync(java.util.function.Supplier<T> supplier) {
        ensureAccepting();
        CompletableFuture<Void> internalDone = new CompletableFuture<>();
        CompletableFuture<T> publicFuture = new CompletableFuture<>();
        PendingTask task = new PendingTask(internalDone, publicFuture);
        pendingTasks.add(task);
        if (closing.get()) {
            pendingTasks.remove(task);
            throw new IllegalStateException("SqlitePlayerDataRepository is already closed");
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
                    internalDone.complete(null);
                    publicFuture.complete(result);
                } catch (Throwable ex) {
                    internalDone.complete(null);
                    publicFuture.completeExceptionally(ex);
                }
            });
        } catch (Throwable ex) {
            pendingTasks.remove(task);
            internalDone.complete(null);
            publicFuture.completeExceptionally(ex);
            throw ex;
        }
        return publicFuture;
    }

    private CompletableFuture<Void> runAsync(Runnable runnable) {
        return supplyAsync(() -> {
            runnable.run();
            return null;
        });
    }

    private <T> T withReadConnection(SqlFunction<Connection, T> action) {
        ensureOpen();
        try (Connection conn = dataSource.getConnection()) {
            return action.apply(conn);
        } catch (SQLException ex) {
            throw new IllegalStateException("SQLite read operation failed", ex);
        }
    }

    private <T> T withWriteTransaction(SqlTxFunction<T> action) {
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

    private void ensureAccepting() {
        if (closing.get() || closed.get()) {
            throw new IllegalStateException("SqlitePlayerDataRepository is already closed");
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("SqlitePlayerDataRepository is already closed");
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

    private static String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return name.trim();
    }

    private static String requireCurrencyId(String currencyId) {
        if (currencyId == null || currencyId.isBlank()) {
            throw new IllegalArgumentException("currencyId must not be null or blank");
        }
        return currencyId.trim();
    }

    private enum CounterColumn {
        DEATHS("total_deaths"),
        REVIVES("total_revives");

        private final String columnName;

        CounterColumn(String columnName) {
            this.columnName = columnName;
        }
    }

    private static final class TransactionContext {
        private boolean rollbackOnly;

        void markRollbackOnly() {
            this.rollbackOnly = true;
        }
    }

    @FunctionalInterface
    private interface SqlFunction<I, O> {
        O apply(I input) throws SQLException;
    }

    @FunctionalInterface
    private interface SqlTxFunction<O> {
        O apply(Connection connection, TransactionContext tx) throws SQLException;
    }
}
