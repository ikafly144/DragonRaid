package net.sabafly.dragonraid.storage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteDatabaseTest {

    @TempDir
    Path tempDir;

    @Test
    void concurrentSupplyAsyncDuringClose_doesNotStallClosePastTaskFinish() throws Exception {
        Path dbPath = tempDir.resolve("close-race.db");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        SqliteDatabase db = new SqliteDatabase(dbPath, 2, executor, Duration.ofSeconds(10));
        try {
            CountDownLatch taskStarted = new CountDownLatch(1);
            CountDownLatch releaseTask = new CountDownLatch(1);
            var running = db.supplyAsync(() -> {
                taskStarted.countDown();
                try {
                    releaseTask.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return "ok";
            });
            assertTrue(taskStarted.await(5, TimeUnit.SECONDS));

            ExecutorService racers = Executors.newFixedThreadPool(2);
            try {
                AtomicBoolean keepRacing = new AtomicBoolean(true);
                Future<?> closer = racers.submit(db::close);
                Future<?> submitter = racers.submit(() -> {
                    while (keepRacing.get()) {
                        try {
                            db.supplyAsync(() -> "late");
                        } catch (IllegalStateException expected) {
                            break;
                        }
                    }
                });

                releaseTask.countDown();
                // Close must finish well under the 10s drain timeout even if a racer hit closing recheck
                closer.get(2, TimeUnit.SECONDS);
                keepRacing.set(false);
                submitter.get(2, TimeUnit.SECONDS);
                assertEqualsOrCancelled(running.get(2, TimeUnit.SECONDS));
            } finally {
                racers.shutdownNow();
            }
        } finally {
            db.close();
            executor.shutdownNow();
        }
    }

    private static void assertEqualsOrCancelled(String result) {
        assertTrue("ok".equals(result));
    }
}
