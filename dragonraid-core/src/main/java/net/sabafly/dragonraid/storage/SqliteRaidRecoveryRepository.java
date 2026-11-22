package net.sabafly.dragonraid.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import net.sabafly.dragonraid.protection.BlockPosKey;
import org.jetbrains.annotations.Nullable;

/**
 * SQLite-backed {@link RaidRecoveryRepository} sharing the plugin's {@link SqliteDatabase}.
 *
 * <p>Per-player async upserts and deletes are serialized per UUID in submission order so an
 * earlier delete can never overwrite a later upsert when executed on a multi-threaded executor.
 * Monotonic snapshot epochs checked inside {@link SqliteDatabase#withWriteTransaction} also ensure
 * stale queued async deletes cannot delete rows written by a newer synchronous snapshot.
 */
public final class SqliteRaidRecoveryRepository implements RaidRecoveryRepository {

    private static final long DEFAULT_DRAIN_TIMEOUT_MS = 3000L;

    private final SqliteDatabase database;
    private final long drainTimeoutMs;
    private final Map<UUID, CompletableFuture<Void>> playerCleanupChains = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<Void>> pendingBlockOps = ConcurrentHashMap.newKeySet();
    private final AtomicLong blockRollbackEpoch = new AtomicLong(0L);
    private final AtomicLong playerCleanupEpoch = new AtomicLong(0L);

    public SqliteRaidRecoveryRepository(SqliteDatabase database) {
        this(database, DEFAULT_DRAIN_TIMEOUT_MS);
    }

    SqliteRaidRecoveryRepository(SqliteDatabase database, long drainTimeoutMs) {
        this.database = Objects.requireNonNull(database, "database");
        if (drainTimeoutMs < 0L) {
            throw new IllegalArgumentException("drainTimeoutMs must not be negative");
        }
        this.drainTimeoutMs = drainTimeoutMs;
        initializeSchema();
    }

    private void initializeSchema() {
        database.withWriteTransaction((conn, tx) -> {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS pending_block_rollbacks (
                        world_id TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        block_data TEXT NOT NULL,
                        PRIMARY KEY (world_id, x, y, z)
                    )
                    """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS pending_player_cleanups (
                        uuid TEXT PRIMARY KEY,
                        game_mode TEXT,
                        invisible INTEGER NOT NULL,
                        collidable INTEGER NOT NULL,
                        invulnerable INTEGER NOT NULL,
                        teleport_to_safe_zone INTEGER NOT NULL,
                        restore_flags INTEGER NOT NULL DEFAULT 1,
                        return_world_id TEXT,
                        return_x REAL,
                        return_y REAL,
                        return_z REAL,
                        return_yaw REAL,
                        return_pitch REAL
                    )
                    """);
            }
            ensurePendingPlayerCleanupColumns(conn);
            return null;
        });
    }

    private static void ensurePendingPlayerCleanupColumns(Connection conn) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(pending_player_cleanups)")) {
            while (rs.next()) {
                String name = rs.getString("name");
                if (name != null) {
                    columns.add(name.toLowerCase(Locale.ROOT));
                }
            }
        }
        Map<String, String> requiredColumns = Map.of(
                "restore_flags", "INTEGER NOT NULL DEFAULT 1",
                "return_world_id", "TEXT",
                "return_x", "REAL",
                "return_y", "REAL",
                "return_z", "REAL",
                "return_yaw", "REAL",
                "return_pitch", "REAL"
        );
        try (Statement alter = conn.createStatement()) {
            for (Map.Entry<String, String> entry : requiredColumns.entrySet()) {
                if (!columns.contains(entry.getKey())) {
                    alter.execute("ALTER TABLE pending_player_cleanups ADD COLUMN "
                            + entry.getKey() + " " + entry.getValue());
                }
            }
        }
    }

    @Override
    public List<PendingBlockRollback> loadPendingBlockRollbacksSync() {
        return database.withReadConnection(conn -> {
            String sql = "SELECT world_id, x, y, z, block_data FROM pending_block_rollbacks";
            List<PendingBlockRollback> list = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new PendingBlockRollback(
                            UUID.fromString(rs.getString("world_id")),
                            rs.getInt("x"),
                            rs.getInt("y"),
                            rs.getInt("z"),
                            rs.getString("block_data")
                    ));
                }
            }
            return List.copyOf(list);
        });
    }

    @Override
    public void replacePendingBlockRollbacksSync(Collection<PendingBlockRollback> entries) {
        Objects.requireNonNull(entries, "entries");
        drainFutures(pendingBlockOps);
        List<PendingBlockRollback> copy = List.copyOf(entries);
        database.withWriteTransaction((conn, tx) -> {
            tx.onCommit(blockRollbackEpoch::incrementAndGet);
            try (Statement clear = conn.createStatement()) {
                clear.execute("DELETE FROM pending_block_rollbacks");
            }
            if (!copy.isEmpty()) {
                String insert = """
                    INSERT INTO pending_block_rollbacks (world_id, x, y, z, block_data)
                    VALUES (?, ?, ?, ?, ?)
                    """;
                try (PreparedStatement ps = conn.prepareStatement(insert)) {
                    for (PendingBlockRollback entry : copy) {
                        ps.setString(1, entry.worldId().toString());
                        ps.setInt(2, entry.x());
                        ps.setInt(3, entry.y());
                        ps.setInt(4, entry.z());
                        ps.setString(5, entry.blockDataString());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> deletePendingBlockRollbacksAsync(Collection<BlockPosKey> keys) {
        Objects.requireNonNull(keys, "keys");
        List<BlockPosKey> copy = List.copyOf(keys);
        if (copy.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        long expectedEpoch = blockRollbackEpoch.get();
        CompletableFuture<Void> op = new CompletableFuture<>();
        pendingBlockOps.add(op);
        try {
            database.runAsync(() -> database.withWriteTransaction((conn, tx) -> {
                if (blockRollbackEpoch.get() != expectedEpoch) {
                    return null;
                }
                String sql = "DELETE FROM pending_block_rollbacks WHERE world_id = ? AND x = ? AND y = ? AND z = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (BlockPosKey key : copy) {
                        ps.setString(1, key.worldId().toString());
                        ps.setInt(2, key.x());
                        ps.setInt(3, key.y());
                        ps.setInt(4, key.z());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                return null;
            })).whenComplete((ok, err) -> {
                pendingBlockOps.remove(op);
                if (err != null) {
                    op.completeExceptionally(err);
                } else {
                    op.complete(null);
                }
            });
        } catch (Throwable ex) {
            pendingBlockOps.remove(op);
            op.completeExceptionally(ex);
        }
        return op;
    }

    @Override
    public Map<UUID, PendingPlayerCleanup> loadPendingPlayerCleanupsSync() {
        return database.withReadConnection(conn -> {
            String sql = """
                SELECT uuid, game_mode, invisible, collidable, invulnerable, teleport_to_safe_zone,
                       restore_flags, return_world_id, return_x, return_y, return_z, return_yaw, return_pitch
                FROM pending_player_cleanups
                """;
            Map<UUID, PendingPlayerCleanup> map = new LinkedHashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = UUID.fromString(rs.getString("uuid"));
                    UUID returnWorldId = readNullableUuid(rs, "return_world_id");
                    Double returnX = readNullableDouble(rs, "return_x");
                    Double returnY = readNullableDouble(rs, "return_y");
                    Double returnZ = readNullableDouble(rs, "return_z");
                    Float returnYaw = readNullableFloat(rs, "return_yaw");
                    Float returnPitch = readNullableFloat(rs, "return_pitch");
                    if (returnWorldId == null || returnX == null || returnY == null || returnZ == null) {
                        returnWorldId = null;
                        returnX = null;
                        returnY = null;
                        returnZ = null;
                        returnYaw = null;
                        returnPitch = null;
                    }
                    map.put(id, new PendingPlayerCleanup(
                            id,
                            rs.getString("game_mode"),
                            rs.getInt("invisible") != 0,
                            rs.getInt("collidable") != 0,
                            rs.getInt("invulnerable") != 0,
                            rs.getInt("teleport_to_safe_zone") != 0,
                            rs.getInt("restore_flags") != 0,
                            returnWorldId,
                            returnX,
                            returnY,
                            returnZ,
                            returnYaw,
                            returnPitch
                    ));
                }
            }
            return map;
        });
    }

    @Override
    public CompletableFuture<Void> upsertPendingPlayerCleanupAsync(PendingPlayerCleanup cleanup) {
        Objects.requireNonNull(cleanup, "cleanup");
        return enqueuePlayerCleanupOp(cleanup.playerId(), (conn, tx) -> {
            String sql = """
                INSERT INTO pending_player_cleanups (
                    uuid, game_mode, invisible, collidable, invulnerable, teleport_to_safe_zone,
                    restore_flags, return_world_id, return_x, return_y, return_z, return_yaw, return_pitch
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    game_mode = excluded.game_mode,
                    invisible = excluded.invisible,
                    collidable = excluded.collidable,
                    invulnerable = excluded.invulnerable,
                    teleport_to_safe_zone = excluded.teleport_to_safe_zone,
                    restore_flags = excluded.restore_flags,
                    return_world_id = excluded.return_world_id,
                    return_x = excluded.return_x,
                    return_y = excluded.return_y,
                    return_z = excluded.return_z,
                    return_yaw = excluded.return_yaw,
                    return_pitch = excluded.return_pitch
                """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bindPendingPlayerCleanup(ps, cleanup);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void replacePendingPlayerCleanupsSync(Collection<PendingPlayerCleanup> cleanups) {
        Objects.requireNonNull(cleanups, "cleanups");
        drainFutures(playerCleanupChains.values());
        List<PendingPlayerCleanup> copy = List.copyOf(cleanups);
        database.withWriteTransaction((conn, tx) -> {
            tx.onCommit(playerCleanupEpoch::incrementAndGet);
            try (Statement clear = conn.createStatement()) {
                clear.execute("DELETE FROM pending_player_cleanups");
            }
            if (!copy.isEmpty()) {
                String sql = """
                    INSERT INTO pending_player_cleanups (
                        uuid, game_mode, invisible, collidable, invulnerable, teleport_to_safe_zone,
                        restore_flags, return_world_id, return_x, return_y, return_z, return_yaw, return_pitch
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (PendingPlayerCleanup cleanup : copy) {
                        bindPendingPlayerCleanup(ps, cleanup);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            return null;
        });
    }

    private static void bindPendingPlayerCleanup(PreparedStatement ps, PendingPlayerCleanup cleanup) throws SQLException {
        ps.setString(1, cleanup.playerId().toString());
        ps.setString(2, cleanup.gameModeName());
        ps.setInt(3, cleanup.invisible() ? 1 : 0);
        ps.setInt(4, cleanup.collidable() ? 1 : 0);
        ps.setInt(5, cleanup.invulnerable() ? 1 : 0);
        ps.setInt(6, cleanup.teleportToSafeZone() ? 1 : 0);
        ps.setInt(7, cleanup.restoreFlags() ? 1 : 0);
        bindNullableUuid(ps, 8, cleanup.returnWorldId());
        bindNullableDouble(ps, 9, cleanup.returnX());
        bindNullableDouble(ps, 10, cleanup.returnY());
        bindNullableDouble(ps, 11, cleanup.returnZ());
        bindNullableFloat(ps, 12, cleanup.returnYaw());
        bindNullableFloat(ps, 13, cleanup.returnPitch());
    }

    private static void bindNullableUuid(PreparedStatement ps, int index, @Nullable UUID value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value.toString());
        }
    }

    private static void bindNullableDouble(PreparedStatement ps, int index, @Nullable Double value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.REAL);
        } else {
            ps.setDouble(index, value);
        }
    }

    private static void bindNullableFloat(PreparedStatement ps, int index, @Nullable Float value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.REAL);
        } else {
            ps.setFloat(index, value);
        }
    }

    private static @Nullable UUID readNullableUuid(ResultSet rs, String column) throws SQLException {
        String raw = rs.getString(column);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static @Nullable Double readNullableDouble(ResultSet rs, String column) throws SQLException {
        Object raw = rs.getObject(column);
        return raw instanceof Number n ? n.doubleValue() : null;
    }

    private static @Nullable Float readNullableFloat(ResultSet rs, String column) throws SQLException {
        Object raw = rs.getObject(column);
        return raw instanceof Number n ? n.floatValue() : null;
    }

    @Override
    public CompletableFuture<Void> deletePendingPlayerCleanupAsync(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return enqueuePlayerCleanupOp(playerId, (conn, tx) -> {
            String sql = "DELETE FROM pending_player_cleanups WHERE uuid = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerId.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    private CompletableFuture<Void> enqueuePlayerCleanupOp(UUID playerId, SqliteDatabase.SqlTxFunction<Void> dbTx) {
        long expectedEpoch = playerCleanupEpoch.get();
        CompletableFuture<Void> gate = new CompletableFuture<>();
        CompletableFuture<Void> next = new CompletableFuture<>();
        CompletableFuture<Void> prev = playerCleanupChains.put(playerId, next);
        CompletableFuture<Void> ready = (prev == null)
                ? gate
                : prev.handle((ok, err) -> null).thenCompose(ignored -> gate);
        CompletableFuture<Void> writeFuture = ready.thenCompose(ignored ->
                database.runAsync(() -> database.withWriteTransaction((conn, tx) -> {
                    if (playerCleanupEpoch.get() != expectedEpoch) {
                        return null;
                    }
                    return dbTx.apply(conn, tx);
                })));
        writeFuture.whenComplete((ok, err) -> {
            playerCleanupChains.remove(playerId, next);
            if (err != null) {
                next.completeExceptionally(err);
            } else {
                next.complete(null);
            }
        });
        gate.complete(null);
        return next;
    }

    private void drainFutures(Collection<CompletableFuture<Void>> futures) {
        CompletableFuture<?>[] active = futures.toArray(CompletableFuture[]::new);
        if (active.length == 0) {
            return;
        }
        try {
            CompletableFuture.allOf(active).get(drainTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignored) {
            // Stale ops that finish after drain timeout are skipped atomically via snapshot epoch checks.
        }
    }
}
