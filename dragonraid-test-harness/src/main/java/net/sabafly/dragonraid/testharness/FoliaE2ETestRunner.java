package net.sabafly.dragonraid.testharness;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sabafly.dragonraid.api.achievement.RaidAchievementService;
import net.sabafly.dragonraid.api.economy.RaidEconomyService;
import net.sabafly.dragonraid.api.enemy.ActorModelSpec;
import net.sabafly.dragonraid.api.enemy.BoneOffset;
import net.sabafly.dragonraid.api.finder.ContentEligibilityResult;
import net.sabafly.dragonraid.api.finder.ContentFinderService;
import net.sabafly.dragonraid.api.finder.ContentUnlockService;
import net.sabafly.dragonraid.api.finder.EligibilityFailureReason;
import net.sabafly.dragonraid.api.item.RaidItemService;
import net.sabafly.dragonraid.api.model.ActorModelController;
import net.sabafly.dragonraid.api.model.ActorModelService;
import net.sabafly.dragonraid.api.pack.ContentPackLoadReport;
import net.sabafly.dragonraid.api.pack.ContentPackLoader;
import net.sabafly.dragonraid.api.party.PartyChatChannel;
import net.sabafly.dragonraid.api.party.PartyHudService;
import net.sabafly.dragonraid.api.party.RaidParty;
import net.sabafly.dragonraid.api.party.RaidPartyService;
import net.sabafly.dragonraid.api.progression.VanillaEndProgressionPolicy;
import net.sabafly.dragonraid.api.progression.VanillaProgressionService;
import net.sabafly.dragonraid.api.reward.ContentUnlockNotifier;
import net.sabafly.dragonraid.api.reward.RaidRewardService;
import net.sabafly.dragonraid.api.reward.RewardApplyResult;
import net.sabafly.dragonraid.api.reward.RewardContext;
import net.sabafly.dragonraid.api.reward.RewardItemDelivery;
import net.sabafly.dragonraid.api.script.EncounterBlueprintRegistry;
import net.sabafly.dragonraid.api.script.EncounterDifficulty;
import net.sabafly.dragonraid.api.script.GimmickActionRegistry;
import net.sabafly.dragonraid.api.script.ScriptHookRegistry;
import net.sabafly.dragonraid.api.service.RaidServiceRegistry;
import net.sabafly.dragonraid.api.stat.RaidStatService;
import net.sabafly.dragonraid.instance.RaidInstanceManager;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.scheduler.TaskHandle;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.ArmorStand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/**
 * Executes deterministic end-to-end verification scenarios inside a live Folia 26.3 server,
 * writes JSON + JUnit XML reports, and triggers clean server shutdown.
 */
final class FoliaE2ETestRunner {

    record ScenarioResult(String name, boolean passed, long durationMs, String errorMessage, String stackTrace) {
    }

    private final Logger logger;
    private final Path reportDir;
    private final DevBlueprintFixtures fixtures;
    private final List<ScenarioResult> results = Collections.synchronizedList(new ArrayList<>());

    FoliaE2ETestRunner(Logger logger, Path reportDir, DevBlueprintFixtures fixtures) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.reportDir = Objects.requireNonNull(reportDir, "reportDir");
        this.fixtures = Objects.requireNonNull(fixtures, "fixtures");
    }

    void runAllAndShutdown() {
        Thread runnerThread = new Thread(() -> {
            logger.info("[E2E] Starting Folia 26.3 real-environment integration test suite...");
            try {
                RaidServiceRegistry registry = Bukkit.getServicesManager().load(RaidServiceRegistry.class);
                runScenario("01_plugin_and_services_bootstrap", () -> verifyBootstrapAndServices(registry));
                if (registry != null) {
                    runScenario("02_content_pack_and_blueprint_catalog", () -> verifyPackAndBlueprints(registry));
                    runScenario("03_folia_brigadier_commands", () -> verifyBrigadierCommands(registry));
                    runScenario("04_sqlite_unlock_economy_and_reward_pipeline", () -> verifySqliteAndRewards(registry));
                    runScenario("05_party_and_finder_eligibility_flow", () -> verifyPartyAndFinder(registry));
                    runScenario("06_worlds_void_instance_arena_and_actor_models", () -> verifyWorldsInstanceAndModels(registry));
                    runScenario("07_vanilla_end_dragon_battle_progression", () -> verifyVanillaEndProgression(registry));
                }
            } catch (Throwable unexpected) {
                logger.log(Level.SEVERE, "[E2E] Unexpected runner failure", unexpected);
                results.add(new ScenarioResult(
                        "unexpected_runner_error",
                        false,
                        0L,
                        String.valueOf(unexpected.getMessage()),
                        formatStackTrace(unexpected)
                ));
            } finally {
                try {
                    writeReports();
                } catch (IOException ioEx) {
                    logger.log(Level.SEVERE, "[E2E] Failed to write E2E reports", ioEx);
                }
                requestCleanShutdown();
            }
        }, "DragonRaid-E2E-Runner");
        runnerThread.setDaemon(true);
        runnerThread.start();
    }

    private void runScenario(String name, ThrowingRunnable action) {
        long start = System.currentTimeMillis();
        logger.info("[E2E] >>> Running scenario: " + name);
        try {
            action.run();
            long elapsed = Math.max(1L, System.currentTimeMillis() - start);
            results.add(new ScenarioResult(name, true, elapsed, "", ""));
            logger.info("[E2E] <<< PASSED: " + name + " (" + elapsed + " ms)");
        } catch (Throwable ex) {
            long elapsed = Math.max(1L, System.currentTimeMillis() - start);
            String msg = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            results.add(new ScenarioResult(name, false, elapsed, msg, formatStackTrace(ex)));
            logger.warning("[E2E] <<< FAILED: " + name + " (" + elapsed + " ms) -> " + msg);
        }
    }

    private void verifyBootstrapAndServices(RaidServiceRegistry registry) {
        PluginManager pm = Bukkit.getPluginManager();
        assertPluginEnabled(pm, "DragonRaid");
        assertPluginEnabled(pm, "packetevents");
        assertPluginEnabled(pm, "Worlds");
        assertPluginEnabled(pm, "BetterModel");

        assertTrue(registry != null, "RaidServiceRegistry was not registered in Bukkit ServicesManager");
        List<Class<?>> expectedServices = List.of(
                RaidScheduler.class,
                RaidInstanceManager.class,
                ActorModelService.class,
                EncounterBlueprintRegistry.class,
                GimmickActionRegistry.class,
                ScriptHookRegistry.class,
                RaidRewardService.class,
                RaidAchievementService.class,
                RaidPartyService.class,
                PartyHudService.class,
                RaidItemService.class,
                ContentUnlockService.class,
                ContentFinderService.class,
                RaidEconomyService.class,
                RaidStatService.class,
                VanillaProgressionService.class,
                VanillaEndProgressionPolicy.class,
                ContentUnlockNotifier.class,
                RewardItemDelivery.class,
                ContentPackLoader.class
        );
        for (Class<?> serviceType : expectedServices) {
            assertTrue(registry.get(serviceType).isPresent(),
                    "Missing service in RaidServiceRegistry: " + serviceType.getName());
        }

        Plugin drPlugin = Objects.requireNonNull(pm.getPlugin("DragonRaid"));
        Path dbFile = drPlugin.getDataPath().resolve("dragonraid.db");
        assertTrue(Files.isRegularFile(dbFile), "SQLite database file missing at " + dbFile);
        assertTrue(Files.isRegularFile(drPlugin.getDataPath().resolve("config.yml")), "config.yml missing");
        assertTrue(Files.isRegularFile(drPlugin.getDataPath().resolve("rewards.yml")), "rewards.yml missing");
    }

    private void verifyPackAndBlueprints(RaidServiceRegistry registry) {
        ContentPackLoader loader = registry.require(ContentPackLoader.class);
        ContentPackLoadReport report = loader.lastReport();
        assertTrue(report.loadedPacks().stream().anyMatch(p -> DevContentPackInstaller.PACK_ID.equals(p.manifest().id())),
                "Expected dev_ender_dragon pack to be loaded");
        assertEquals(3, report.totalContents(), "Expected 3 contents in dev_ender_dragon");
        assertEquals(2, report.totalAchievements(), "Expected 2 achievements in dev_ender_dragon");
        assertEquals(4, report.totalRewardTables(), "Expected 4 reward tables in dev_ender_dragon");
        assertEquals(1, report.totalProgressionRules(), "Expected 1 progression rule in dev_ender_dragon");

        ContentFinderService finder = registry.require(ContentFinderService.class);
        assertTrue(finder.getContent(DevContentPackInstaller.CONTENT_PRACTICE).isPresent(), "Missing practice content");
        assertTrue(finder.getContent(DevContentPackInstaller.CONTENT_NORMAL).isPresent(), "Missing normal content");
        assertTrue(finder.getContent(DevContentPackInstaller.CONTENT_HARD).isPresent(), "Missing hard content");

        EncounterBlueprintRegistry blueprints = registry.require(EncounterBlueprintRegistry.class);
        assertTrue(blueprints.get(DevBlueprintFixtures.BLUEPRINT_PRACTICE).isPresent(), "Missing practice blueprint");
        assertTrue(blueprints.get(DevBlueprintFixtures.BLUEPRINT_NORMAL).isPresent(), "Missing normal blueprint");
        assertTrue(blueprints.get(DevBlueprintFixtures.BLUEPRINT_HARD).isPresent(), "Missing hard blueprint");
    }

    private void verifyBrigadierCommands(RaidServiceRegistry registry) throws Exception {
        RaidScheduler scheduler = registry.require(RaidScheduler.class);
        ContentPackLoader loader = registry.require(ContentPackLoader.class);
        long generationBeforeReload = loader.catalogSnapshot().generation();

        List<String> commands = List.of(
                "dr",
                "dr admin packs",
                "dr admin progression status",
                "dr admin reward list",
                "dr admin reload"
        );
        for (String cmd : commands) {
            Boolean dispatched = callOnGlobal(scheduler, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd))
                    .get(15L, TimeUnit.SECONDS);
            assertTrue(Boolean.TRUE.equals(dispatched), "Brigadier command returned false: /" + cmd);
        }

        // Deterministically await async ContentPackLoader.reload() completion via catalog generation increment.
        long deadlineMs = System.currentTimeMillis() + 10_000L;
        while (loader.catalogSnapshot().generation() <= generationBeforeReload
                && System.currentTimeMillis() < deadlineMs) {
            Thread.sleep(50L);
        }
        assertTrue(loader.catalogSnapshot().generation() > generationBeforeReload,
                "Expected catalog generation to advance after /dr admin reload");
        assertEquals(3, loader.lastReport().totalContents(), "Content count should remain 3 after /dr admin reload");
        EncounterBlueprintRegistry blueprints = registry.require(EncounterBlueprintRegistry.class);
        assertTrue(blueprints.get(DevBlueprintFixtures.BLUEPRINT_NORMAL).isPresent(),
                "Blueprints should remain registered after /dr admin reload");
    }

    private void verifySqliteAndRewards(RaidServiceRegistry registry) throws Exception {
        UUID testPlayerId = UUID.randomUUID();
        String testPlayerName = "E2EVerifier";

        ContentUnlockService unlockService = registry.require(ContentUnlockService.class);
        assertTrue(!unlockService.isUnlocked(testPlayerId, DevContentPackInstaller.UNLOCK_KEY_NORMAL),
                "Fresh player must not have normal raid unlocked");
        Boolean granted = unlockService.unlockAsync(testPlayerId, testPlayerName, DevContentPackInstaller.UNLOCK_KEY_NORMAL)
                .get(10L, TimeUnit.SECONDS);
        assertTrue(Boolean.TRUE.equals(granted), "unlockAsync should return true on first grant");
        assertTrue(unlockService.isUnlocked(testPlayerId, DevContentPackInstaller.UNLOCK_KEY_NORMAL),
                "isUnlocked should return true after unlockAsync");

        // Evict in-memory cache and reload from real SQLite to prove durable persistence.
        unlockService.evictPlayer(testPlayerId);
        Set<String> reloadedUnlocks = unlockService.loadPlayer(testPlayerId).get(10L, TimeUnit.SECONDS);
        assertTrue(reloadedUnlocks.contains(DevContentPackInstaller.UNLOCK_KEY_NORMAL),
                "SQLite reload must preserve granted content unlock key");

        RaidEconomyService economy = registry.require(RaidEconomyService.class);
        long now = System.currentTimeMillis();
        String idemKey = "e2e:deposit:1";
        assertTrue(economy.depositIdempotentAsync(
                        testPlayerId, testPlayerName, DevBlueprintFixtures.CURRENCY_DRAGON_TOKEN, 120L, idemKey, now)
                .get(10L, TimeUnit.SECONDS), "Initial idempotent deposit must succeed");
        // Duplicate idempotency key must not double-credit!
        economy.depositIdempotentAsync(
                        testPlayerId, testPlayerName, DevBlueprintFixtures.CURRENCY_DRAGON_TOKEN, 120L, idemKey, now)
                .get(10L, TimeUnit.SECONDS);
        long balAfterDedup = economy.balanceAsync(testPlayerId, DevBlueprintFixtures.CURRENCY_DRAGON_TOKEN)
                .get(10L, TimeUnit.SECONDS);
        assertEquals(120L, balAfterDedup, "Idempotent deposit must prevent duplicate credit in SQLite");
        assertTrue(economy.withdrawAsync(testPlayerId, DevBlueprintFixtures.CURRENCY_DRAGON_TOKEN, 20L)
                .get(10L, TimeUnit.SECONDS), "withdrawAsync must succeed when balance is sufficient");
        assertEquals(100L, economy.balanceAsync(testPlayerId, DevBlueprintFixtures.CURRENCY_DRAGON_TOKEN)
                .get(10L, TimeUnit.SECONDS), "Balance should be 100 after withdrawing 20");

        // Evaluate and apply pack reward table (CONTENT_UNLOCK + SCRIPT_HOOK + CURRENCY + offline item retry safe).
        RaidRewardService rewardService = registry.require(RaidRewardService.class);
        int hooksBefore = fixtures.clearHookInvocations();
        RewardContext rewardCtx = RewardContext.builder(testPlayerId)
                .playerName(testPlayerName)
                .sourceType("ENCOUNTER_CLEAR")
                .sourceId(DevBlueprintFixtures.BLUEPRINT_NORMAL)
                .difficulty(EncounterDifficulty.NORMAL)
                .firstClear(true)
                .partySize(1)
                .durationMillis(45_000L)
                .build();
        RewardApplyResult applyResult = rewardService.evaluateAndApply(
                        DevContentPackInstaller.REWARD_TABLE_NORMAL_CLEAR,
                        rewardCtx,
                        new Random(42L))
                .get(10L, TimeUnit.SECONDS);
        assertTrue(applyResult != null, "RewardApplyResult must not be null");
        assertTrue(unlockService.isUnlocked(testPlayerId, DevContentPackInstaller.UNLOCK_KEY_HARD),
                "Applying normal clear rewards must unlock raid:dev_dragon_hard");
        assertEquals(125L, economy.balanceAsync(testPlayerId, DevBlueprintFixtures.CURRENCY_DRAGON_TOKEN)
                .get(10L, TimeUnit.SECONDS), "Reward table must grant +25 dragon_token");
        assertTrue(fixtures.clearHookInvocations() > hooksBefore,
                "SCRIPT_HOOK action must execute during reward application");
        assertTrue(fixtures.lastHookThreadName().contains("DragonRaid-Script"),
                "SCRIPT_HOOK must execute on dedicated DragonRaid-Script thread, got: " + fixtures.lastHookThreadName());
    }

    private void verifyPartyAndFinder(RaidServiceRegistry registry) throws Exception {
        UUID leaderId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        RaidPartyService partyService = registry.require(RaidPartyService.class);
        ContentUnlockService unlockService = registry.require(ContentUnlockService.class);
        ContentFinderService finderService = registry.require(ContentFinderService.class);

        // Before unlock, eligibility check on normal raid must report MEMBER_MISSING_UNLOCK (and NOT BLUEPRINT_MISSING).
        ContentEligibilityResult lockedCheck = finderService.evaluateEligibility(leaderId, DevContentPackInstaller.CONTENT_NORMAL);
        assertTrue(!lockedCheck.eligible(), "Ununlocked player must not be eligible");
        assertTrue(lockedCheck.issues().stream().anyMatch(i -> i.reason() == EligibilityFailureReason.MEMBER_MISSING_UNLOCK),
                "Expected MEMBER_MISSING_UNLOCK issue before unlock");
        assertTrue(lockedCheck.issues().stream().noneMatch(i -> i.reason() == EligibilityFailureReason.BLUEPRINT_MISSING),
                "Blueprint must be resolved without BLUEPRINT_MISSING");

        unlockService.unlockAsync(leaderId, "Leader", DevContentPackInstaller.UNLOCK_KEY_NORMAL).get(10L, TimeUnit.SECONDS);
        ContentEligibilityResult unlockedCheck = finderService.evaluateEligibility(leaderId, DevContentPackInstaller.CONTENT_NORMAL);
        assertTrue(unlockedCheck.issues().stream().noneMatch(i -> i.reason() == EligibilityFailureReason.MEMBER_MISSING_UNLOCK),
                "MEMBER_MISSING_UNLOCK must clear once unlock key is granted");

        // Party lifecycle: create -> invite -> accept -> chat toggle -> leader disconnect immediate handoff -> disband
        RaidParty created = partyService.createParty(leaderId);
        assertEquals(leaderId, created.leaderId(), "Creator should be party leader");
        assertTrue(partyService.invite(leaderId, memberId), "Leader invite should succeed");
        assertTrue(partyService.acceptInvite(memberId, created.partyId()), "Member acceptInvite should succeed");
        assertEquals(PartyChatChannel.PARTY, partyService.toggleChatChannel(leaderId), "Toggle should switch to PARTY channel");
        assertTrue(partyService.markDisconnected(leaderId), "Leader disconnect grace should activate");
        RaidParty afterDisconnect = partyService.partyById(created.partyId()).orElseThrow();
        assertEquals(memberId, afterDisconnect.leaderId(), "Leadership must transfer immediately when leader disconnects");
        assertTrue(partyService.reconnect(leaderId), "Disconnected former leader should rejoin within grace window");
        assertTrue(partyService.disband(memberId), "Current leader disband should succeed");
    }

    private void verifyWorldsInstanceAndModels(RaidServiceRegistry registry) throws Exception {
        RaidInstanceManager instanceManager = registry.require(RaidInstanceManager.class);
        RaidScheduler scheduler = registry.require(RaidScheduler.class);
        ActorModelService modelService = registry.require(ActorModelService.class);

        assertTrue(instanceManager.activeInstance() == null, "No raid instance should be active initially");
        RaidInstanceManager.RaidInstance instance = instanceManager.getOrCreateInstance().get(45L, TimeUnit.SECONDS);
        assertTrue(instance != null && instance.world() != null, "Worlds failed to create temporary raid instance");

        World instWorld = instance.world();
        UUID instWorldId = instWorld.getUID();
        assertTrue(RaidInstanceManager.isManagedInstanceWorldName(instWorld.getName()),
                "Unexpected instance world name: " + instWorld.getName());
        assertEquals(World.Environment.THE_END, instWorld.getEnvironment(), "Instance world must be THE_END dimension");
        assertEquals(8, instance.pillarCrystalLocations().size(), "Expected 8 obsidian pillar crystal locations");

        // Verify real block placements and EntityScheduler/ActorModelService on Folia region threads!
        Location center = instance.arenaCenter();
        callOnRegion(scheduler, center, () -> {
            assertTrue(Bukkit.isOwnedByCurrentRegion(center), "Task must run on Folia region owning arena center");
            assertEquals(Material.BEDROCK, instWorld.getBlockAt(0, 63, 0).getType(), "Arena sub-floor must be BEDROCK");
            assertEquals(Material.CRYING_OBSIDIAN, instWorld.getBlockAt(0, 64, 0).getType(),
                    "Arena center marker must be CRYING_OBSIDIAN");
            assertEquals(Material.PURPUR_BLOCK, instWorld.getBlockAt(20, 64, 0).getType(),
                    "Arena mid-ring at r=20 must be PURPUR_BLOCK");
            return null;
        }).get(15L, TimeUnit.SECONDS);

        for (Location crystalLoc : instance.pillarCrystalLocations()) {
            callOnRegion(scheduler, crystalLoc, () -> {
                Material cap = instWorld.getBlockAt(
                        crystalLoc.getBlockX(),
                        crystalLoc.getBlockY() - 1,
                        crystalLoc.getBlockZ()
                ).getType();
                assertEquals(Material.BEDROCK, cap, "Each obsidian pillar top cap must be BEDROCK");
                return null;
            }).get(15L, TimeUnit.SECONDS);
        }

        // Spawn a real entity on the arena center region and bind both VANILLA and BETTER_MODEL controllers on EntityScheduler.
        CompletableFuture<Void> entityModelFuture = new CompletableFuture<>();
        scheduler.runAtRegion(center, () -> {
            try {
                ArmorStand stand = instWorld.spawn(new Location(instWorld, 0.5, 65.0, 0.5), ArmorStand.class);
                scheduler.runAtEntity(stand, () -> {
                    try {
                        assertTrue(Bukkit.isOwnedByCurrentRegion(stand),
                                "Entity task must run on Folia region owning entity");
                        ActorModelController vanillaCtrl = modelService.bindEntity(
                                stand,
                                ActorModelSpec.vanilla(1.0, Map.of("head", new BoneOffset(0.0, 1.8, 0.0)))
                        );
                        Location headLoc = vanillaCtrl.boneLocation("head");
                        assertTrue(headLoc != null && Math.abs(headLoc.getY() - 66.8) < 1e-4,
                                "Unexpected vanilla bone Y location: " + headLoc);
                        vanillaCtrl.close();

                        ActorModelController bmFallbackCtrl = modelService.bindEntity(
                                stand,
                                ActorModelSpec.betterModel("e2e_missing_model_fallback_check", 1.2)
                        );
                        assertTrue(bmFallbackCtrl.boneLocation("origin") != null,
                                "BetterModel controller must fall back gracefully when model asset is absent");
                        bmFallbackCtrl.close();
                        stand.remove();
                        entityModelFuture.complete(null);
                    } catch (Throwable t) {
                        stand.remove();
                        entityModelFuture.completeExceptionally(t);
                    }
                }, () -> entityModelFuture.completeExceptionally(new IllegalStateException("Test ArmorStand retired early")));
            } catch (Throwable t) {
                entityModelFuture.completeExceptionally(t);
            }
        });
        entityModelFuture.get(15L, TimeUnit.SECONDS);

        // Tear down temporary instance and confirm unloading & deletion via TheNextLvl Worlds.
        World overworld = Bukkit.getWorlds().getFirst();
        instanceManager.destroyInstance(instance, overworld.getSpawnLocation()).get(30L, TimeUnit.SECONDS);
        assertTrue(instanceManager.activeInstance() == null, "activeInstance() must be null after destroyInstance");
        assertTrue(Bukkit.getWorld(instWorldId) == null, "Temporary instance world must be unloaded and deleted");
    }

    private void verifyVanillaEndProgression(RaidServiceRegistry registry) throws Exception {
        VanillaProgressionService progressionService = registry.require(VanillaProgressionService.class);
        RaidScheduler scheduler = registry.require(RaidScheduler.class);

        World endWorld = Bukkit.getWorlds().stream()
                .filter(w -> w.getEnvironment() == World.Environment.THE_END
                        && !RaidInstanceManager.isManagedInstanceWorldName(w.getName()))
                .findFirst()
                .orElse(null);
        assertTrue(endWorld != null, "Expected vanilla THE_END world (world_the_end) to be loaded on Folia server");

        String endWorldName = endWorld.getName();
        Boolean synced = progressionService.synchronizeDragonDefeated(endWorldName, true, true, true)
                .get(20L, TimeUnit.SECONDS);
        assertTrue(Boolean.TRUE.equals(synced), "synchronizeDragonDefeated must return true");

        Boolean killed = progressionService.isVanillaDragonPreviouslyKilled(endWorldName)
                .get(15L, TimeUnit.SECONDS);
        assertTrue(Boolean.TRUE.equals(killed), "DragonBattle.hasBeenPreviouslyKilled() must be true after sync");

        VanillaProgressionService.GatewayExpansionResult gatewayResult =
                progressionService.expandEndGatewaysToTarget(endWorldName, 1, 20).get(20L, TimeUnit.SECONDS);
        assertTrue(gatewayResult.finalGatewayCount() >= 1,
                "Expected at least 1 End Gateway after expandEndGatewaysToTarget, got: " + gatewayResult.finalGatewayCount());

        // Double-check directly on the Folia region thread at (0, 64, 0) in world_the_end.
        Location endOrigin = new Location(endWorld, 0.0, 64.0, 0.0);
        Boolean battleVerified = callOnRegion(scheduler, endOrigin, () -> {
            DragonBattle battle = endWorld.getEnderDragonBattle();
            return battle != null && battle.hasBeenPreviouslyKilled();
        }).get(15L, TimeUnit.SECONDS);
        assertTrue(Boolean.TRUE.equals(battleVerified), "Direct Folia region check of DragonBattle failed");
    }

    private void writeReports() throws IOException {
        Files.createDirectories(reportDir);
        List<ScenarioResult> snapshot;
        synchronized (results) {
            snapshot = List.copyOf(results);
        }
        int total = snapshot.size();
        long failed = snapshot.stream().filter(r -> !r.passed()).count();
        long totalMs = snapshot.stream().mapToLong(ScenarioResult::durationMs).sum();

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"total\": ").append(total).append(",\n");
        json.append("  \"passed\": ").append(total - failed).append(",\n");
        json.append("  \"failed\": ").append(failed).append(",\n");
        json.append("  \"durationMs\": ").append(totalMs).append(",\n");
        json.append("  \"scenarios\": [\n");
        for (int i = 0; i < snapshot.size(); i++) {
            ScenarioResult r = snapshot.get(i);
            json.append("    {\n");
            json.append("      \"name\": \"").append(escapeJson(r.name())).append("\",\n");
            json.append("      \"passed\": ").append(r.passed()).append(",\n");
            json.append("      \"durationMs\": ").append(r.durationMs()).append(",\n");
            json.append("      \"error\": \"").append(escapeJson(r.errorMessage())).append("\"\n");
            json.append("    }").append(i + 1 < snapshot.size() ? "," : "").append("\n");
        }
        json.append("  ]\n}\n");
        Files.writeString(reportDir.resolve("e2e-results.json"), json.toString(), StandardCharsets.UTF_8);

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append(String.format(
                Locale.ROOT,
                "<testsuite name=\"FoliaRealServerE2ETest\" tests=\"%d\" failures=\"%d\" errors=\"0\" skipped=\"0\" time=\"%.3f\">\n",
                total,
                failed,
                totalMs / 1000.0
        ));
        for (ScenarioResult r : snapshot) {
            xml.append(String.format(
                    Locale.ROOT,
                    "  <testcase classname=\"net.sabafly.dragonraid.testharness.FoliaRealServerE2ETest\" name=\"%s\" time=\"%.3f\"",
                    escapeXml(r.name()),
                    r.durationMs() / 1000.0
            ));
            if (r.passed()) {
                xml.append("/>\n");
            } else {
                xml.append(">\n");
                xml.append("    <failure message=\"").append(escapeXml(r.errorMessage())).append("\">")
                        .append(escapeXml(r.stackTrace()))
                        .append("</failure>\n");
                xml.append("  </testcase>\n");
            }
        }
        xml.append("</testsuite>\n");
        Files.writeString(
                reportDir.resolve("TEST-FoliaRealServerE2ETest.xml"),
                xml.toString(),
                StandardCharsets.UTF_8
        );
        logger.info("[E2E] Wrote E2E reports to " + reportDir + " (total=" + total + ", failed=" + failed + ")");
    }

    private void requestCleanShutdown() {
        Plugin harness = Bukkit.getPluginManager().getPlugin("DragonRaidTestHarness");
        if (harness != null && harness.isEnabled()) {
            Bukkit.getGlobalRegionScheduler().run(harness, ignored -> {
                logger.info("[E2E] Requesting clean Folia server shutdown...");
                Bukkit.shutdown();
            });
        } else {
            Bukkit.shutdown();
        }
    }

    private static <T> CompletableFuture<T> callOnGlobal(RaidScheduler scheduler, Supplier<T> action) {
        CompletableFuture<T> future = new CompletableFuture<>();
        TaskHandle handle = scheduler.runGlobal(() -> {
            try {
                future.complete(action.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        if (handle == null) {
            future.completeExceptionally(new IllegalStateException("Failed to schedule global task"));
        }
        return future;
    }

    private static <T> CompletableFuture<T> callOnRegion(
            RaidScheduler scheduler,
            Location location,
            Supplier<T> action) {
        CompletableFuture<T> future = new CompletableFuture<>();
        TaskHandle handle = scheduler.runAtRegion(location, () -> {
            try {
                future.complete(action.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        if (handle == null) {
            future.completeExceptionally(new IllegalStateException("Failed to schedule region task at " + location));
        }
        return future;
    }

    private static void assertPluginEnabled(PluginManager pm, String pluginName) {
        Plugin p = pm.getPlugin(pluginName);
        assertTrue(p != null && p.isEnabled(), "Required plugin is not enabled: " + pluginName);
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
        }
    }

    private static String formatStackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private static String escapeXml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
