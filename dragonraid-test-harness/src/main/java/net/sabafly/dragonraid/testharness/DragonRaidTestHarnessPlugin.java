package net.sabafly.dragonraid.testharness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import net.sabafly.dragonraid.api.pack.ContentPackLoader;
import net.sabafly.dragonraid.api.service.RaidServiceRegistry;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Companion Paper/Folia plugin supporting:
 * <ul>
 *   <li><b>Interactive mode</b> ({@code -Ddragonraid.harness.mode=interactive}, default):
 *   installs the {@code dev_ender_dragon} Content Pack, registers verification {@code EncounterBlueprint}s,
 *   and exposes {@code /drtest} helper commands for local multi-client testing.</li>
 *   <li><b>Automated E2E mode</b> ({@code -Ddragonraid.harness.mode=auto}):
 *   runs {@link FoliaE2ETestRunner} once {@link ServerLoadEvent} fires, writes JUnit XML &amp; JSON reports,
 *   enforces a hard watchdog timeout, and shuts down the Folia server cleanly.</li>
 * </ul>
 */
public final class DragonRaidTestHarnessPlugin extends JavaPlugin implements Listener {

    public static final String PROP_MODE = "dragonraid.harness.mode";
    public static final String PROP_REPORT_DIR = "dragonraid.harness.reportDir";
    public static final String PROP_TIMEOUT_SECONDS = "dragonraid.harness.timeoutSeconds";
    public static final String PROP_OVERWRITE_PACK = "dragonraid.harness.overwritePack";

    private final DevBlueprintFixtures fixtures = new DevBlueprintFixtures();
    private final AtomicBoolean suiteStarted = new AtomicBoolean(false);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private boolean autoMode;
    private Path reportDir;

    @Override
    public void onEnable() {
        String mode = System.getProperty(PROP_MODE, "interactive").trim().toLowerCase(Locale.ROOT);
        this.autoMode = "auto".equals(mode);
        String configuredReportDir = System.getProperty(PROP_REPORT_DIR, "");
        this.reportDir = configuredReportDir.isBlank()
                ? getDataPath().resolve("e2e-report")
                : Path.of(configuredReportDir);

        if (autoMode) {
            writeMarkerFile("harness-enabled.marker", "enabled");
            startWatchdog(Integer.getInteger(PROP_TIMEOUT_SECONDS, 120));
        }

        RaidServiceRegistry registry = getServer().getServicesManager().load(RaidServiceRegistry.class);
        if (registry == null) {
            getLogger().severe("RaidServiceRegistry is unavailable; DragonRaid may have failed to enable.");
            if (autoMode) {
                getServer().getPluginManager().registerEvents(this, this);
            }
            return;
        }

        try {
            boolean overwrite = autoMode || Boolean.getBoolean(PROP_OVERWRITE_PACK);
            ContentPackLoader packLoader = registry.require(ContentPackLoader.class);
            Path installedPack = DevContentPackInstaller.install(packLoader.contentsRoot(), overwrite);
            fixtures.registerAll(registry);
            packLoader.reload();
            getLogger().info("Installed verification pack at " + installedPack + " and registered dev blueprints (mode=" + mode + ").");
        } catch (IOException | RuntimeException ex) {
            getLogger().log(Level.SEVERE, "Failed to initialize DragonRaid test harness fixtures", ex);
        }

        if (autoMode) {
            getServer().getPluginManager().registerEvents(this, this);
        } else {
            new DrTestCommandRegistrar(registry).register(this);
        }
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        if (!autoMode || !suiteStarted.compareAndSet(false, true)) {
            return;
        }
        new FoliaE2ETestRunner(getLogger(), reportDir, fixtures).runAllAndShutdown();
    }

    @Override
    public void onDisable() {
        shuttingDown.set(true);
        if (autoMode) {
            writeMarkerFile("harness-disabled.marker", "disabled");
        }
    }

    private void startWatchdog(int timeoutSeconds) {
        int clampedSeconds = Math.max(15, timeoutSeconds);
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(clampedSeconds * 1000L);
            } catch (InterruptedException ignored) {
                return;
            }
            if (shuttingDown.get()) {
                return;
            }
            getLogger().severe("[E2E Watchdog] Timed out after " + clampedSeconds + "s! Dumping thread stacks and halting server...");
            StringBuilder dump = new StringBuilder();
            for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                dump.append("Thread: ").append(entry.getKey().getName())
                        .append(" [").append(entry.getKey().getState()).append("]\n");
                for (StackTraceElement el : entry.getValue()) {
                    dump.append("  at ").append(el).append('\n');
                }
            }
            writeMarkerFile("watchdog-timeout-threaddump.txt", dump.toString());
            try {
                Bukkit.shutdown();
            } catch (Throwable ignored) {
                // Ignore if server is unresponsive.
            }
        }, "DragonRaid-E2E-Watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private void writeMarkerFile(String fileName, String content) {
        try {
            Files.createDirectories(reportDir);
            Files.writeString(reportDir.resolve(fileName), content + "\n", StandardCharsets.UTF_8);
        } catch (IOException ex) {
            getLogger().log(Level.WARNING, "Failed to write marker file " + fileName, ex);
        }
    }
}
