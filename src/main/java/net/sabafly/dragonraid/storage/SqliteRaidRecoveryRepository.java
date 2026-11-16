package net.sabafly.dragonraid.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.sabafly.dragonraid.protection.BlockPosKey;

/**
 * SQLite-backed {@link RaidRecoveryRepository} sharing the plugin's {@link SqliteDatabase}.
 *
 * <p>Per-player async upserts and deletes are serialized per UUID in submission order so an
 * earlier delete can never overwrite a later upsert when executed on a multi-threaded executor.
 */
public final class SqliteRaidRecoveryRepository implements RaidRecoveryRepository {

    private static final long DRAIN_TIMEOUT_MS = 3000L;

    private final SqliteDatabase database;
    private final Map<UUID, CompletableFuture<Void>> playerCleanupChains = new ConcurrentHashMap<>();

    public SqliteRaidRecoveryRepository(SqliteDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
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
                        teleport_to_safe_zone INTEGER NOT NULL
                    )
                    """);
            }
            return null;
        });
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
        List<PendingBlockRollback> copy = List.copyOf(entries);
        database.withWriteTransaction((conn, tx) -> {
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
        return database.runAsync(() -> database.withWriteTransaction((conn, tx) -> {
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
        }));
    }

    @Override
    public Map<UUID, PendingPlayerCleanup> loadPendingPlayerCleanupsSync() {
        return database.withReadConnection(conn -> {
            String sql = """
                SELECT uuid, game_mode, invisible, collidable, invulnerable, teleport_to_safe_zone
                FROM pending_player_cleanups
                """;
            Map<UUID, PendingPlayerCleanup> map = new LinkedHashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = UUID.fromString(rs.getString("uuid"));
                    map.put(id, new PendingPlayerCleanup(
                            id,
                            rs.getString("game_mode"),
                            rs.getInt("invisible") != 0,
                            rs.getInt("collidable") != 0,
                            rs.getInt("invulnerable") != 0,
                            rs.getInt("teleport_to_safe_zone") != 0
                    ));
                }
            }
            return map;
        });
    }

    @Override
    public CompletableFuture<Void> upsertPendingPlayerCleanupAsync(PendingPlayerCleanup cleanup) {
        Objects.requireNonNull(cleanup, "cleanup");
        return enqueuePlayerCleanupOp(cleanup.playerId(), () -> database.withWriteTransaction((conn, tx) -> {
            String sql = """
                INSERT INTO pending_player_cleanups (
                    uuid, game_mode, invisible, collidable, invulnerable, teleport_to_safe_zone
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    game_mode = excluded.game_mode,
                    invisible = excluded.invisible,
                    collidable = excluded.collidable,
                    invulnerable = excluded.invulnerable,
                    teleport_to_safe_zone = excluded.teleport_to_safe_zone
                """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, cleanup.playerId().toString());
                ps.setString(2, cleanup.gameModeName());
                ps.setInt(3, cleanup.invisible() ? 1 : 0);
                ps.setInt(4, cleanup.collidable() ? 1 : 0);
                ps.setInt(5, cleanup.invulnerable() ? 1 : 0);
                ps.setInt(6, cleanup.teleportToSafeZone() ? 1 : 0);
                ps.executeUpdate();
            }
            return null;
        }));
    }

    @Override
    public void replacePendingPlayerCleanupsSync(Collection<PendingPlayerCleanup> cleanups) {
        Objects.requireNonNull(cleanups, "cleanups");
        drainPlayerCleanupChains();
        List<PendingPlayerCleanup> copy = List.copyOf(cleanups);
        database.withWriteTransaction((conn, tx) -> {
            try (Statement clear = conn.createStatement()) {
                clear.execute("DELETE FROM pending_player_cleanups");
            }
            if (!copy.isEmpty()) {
                String sql = """
                    INSERT INTO pending_player_cleanups (
                        uuid, game_mode, invisible, collidable, invulnerable, teleport_to_safe_zone
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (PendingPlayerCleanup cleanup : copy) {
                        ps.setString(1, cleanup.playerId().toString());
                        ps.setString(2, cleanup.gameModeName());
                        ps.setInt(3, cleanup.invisible() ? 1 : 0);
                        ps.setInt(4, cleanup.collidable() ? 1 : 0);
                        ps.setInt(5, cleanup.invulnerable() ? 1 : 0);
                        ps.setInt(6, cleanup.teleportToSafeZone() ? 1 : 0);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> deletePendingPlayerCleanupAsync(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return enqueuePlayerCleanupOp(playerId, () -> database.withWriteTransaction((conn, tx) -> {
            String sql = "DELETE FROM pending_player_cleanups WHERE uuid = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerId.toString());
                ps.executeUpdate();
            }
            return null;
        }));
    }

    private CompletableFuture<Void> enqueuePlayerCleanupOp(UUID playerId, Runnable dbWrite) {
        CompletableFuture<Void> gate = new CompletableFuture<>();
        CompletableFuture<Void> next = new CompletableFuture<>();
        CompletableFuture<Void> prev = playerCleanupChains.put(playerId, next);
        CompletableFuture<Void> ready = (prev == null)
                ? gate
                : prev.handle((ok, err) -> null).thenCompose(ignored -> gate);
        CompletableFuture<Void> writeFuture = ready.thenCompose(ignored -> database.runAsync(dbWrite));
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

    private void drainPlayerCleanupChains() {
        CompletableFuture<?>[] active = playerCleanupChains.values().toArray(CompletableFuture[]::new);
        if (active.length == 0) {
            return;
        }
        try {
            CompletableFuture.allOf(active).get(DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignored) {
            // Proceed to synchronous snapshot write so shutdown still saves latest state.
        }
    }
}
