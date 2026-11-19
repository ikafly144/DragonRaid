package net.sabafly.dragonraid.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.kyori.adventure.title.Title;
import net.sabafly.dragonraid.achievement.DefaultRaidAchievementService;
import net.sabafly.dragonraid.api.achievement.RaidAchievementService;
import net.sabafly.dragonraid.api.enemy.EnemyRole;
import net.sabafly.dragonraid.api.event.EncounterOutcome;
import net.sabafly.dragonraid.api.event.EncounterResultSnapshot;
import net.sabafly.dragonraid.api.event.ParticipantCombatMetrics;
import net.sabafly.dragonraid.api.finder.ContentFinderService;
import net.sabafly.dragonraid.api.finder.ContentUnlockService;
import net.sabafly.dragonraid.api.pack.ContentPackLoadReport;
import net.sabafly.dragonraid.api.progression.VanillaEndProgressionPolicy;
import net.sabafly.dragonraid.api.progression.VanillaProgressionService;
import net.sabafly.dragonraid.api.reward.ContentUnlockNotifier;
import net.sabafly.dragonraid.api.reward.CustomItemGrant;
import net.sabafly.dragonraid.api.reward.RaidRewardService;
import net.sabafly.dragonraid.api.reward.RewardApplyResult;
import net.sabafly.dragonraid.api.reward.RewardBundle;
import net.sabafly.dragonraid.api.reward.RewardContext;
import net.sabafly.dragonraid.api.reward.RewardCurrencySink;
import net.sabafly.dragonraid.api.script.EncounterDifficulty;
import net.sabafly.dragonraid.api.script.ScriptHookRegistry;
import net.sabafly.dragonraid.api.service.RaidServiceRegistry;
import net.sabafly.dragonraid.config.ConfigManager;
import net.sabafly.dragonraid.core.DefaultRaidServiceRegistry;
import net.sabafly.dragonraid.finder.DefaultContentFinderService;
import net.sabafly.dragonraid.finder.DefaultContentUnlockService;
import net.sabafly.dragonraid.i18n.MessageService;
import net.sabafly.dragonraid.instance.RaidInstanceManager;
import net.sabafly.dragonraid.reward.DefaultRaidRewardService;
import net.sabafly.dragonraid.reward.DefaultRewardApplier;
import net.sabafly.dragonraid.reward.FoliaRewardItemDelivery;
import net.sabafly.dragonraid.reward.PureRewardTableEvaluator;
import net.sabafly.dragonraid.reward.RewardDispatchListener;
import net.sabafly.dragonraid.scheduler.RaidScheduler;
import net.sabafly.dragonraid.scheduler.TaskHandle;
import net.sabafly.dragonraid.script.DefaultScriptHookRegistry;
import net.sabafly.dragonraid.script.pack.DefaultContentPackLoader;
import net.sabafly.dragonraid.storage.PointTransactionResult;
import net.sabafly.dragonraid.storage.SqlitePlayerDataRepository;
import net.sabafly.dragonraid.visual.PacketEventsContentUnlockNotifier;
import org.bukkit.Location;
import org.bukkit.PortalType;
import org.bukkit.World;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end integration test uniting YAML Content Packs, caller-referenced Loot-Table rewards,
 * cross-pack {@code inject-into} extensions, pure evaluation, atomic claim persistence,
 * SQLite persistence, achievement triggers, unlock &amp; toast notification, Content Finder gates,
 * vanilla End portal restrictions, DragonBattle/gateway sync, and restart persistence.
 */
class EndToEndProgressionIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void fullProgressionFlowWithLootTableRewardsCrossPackExtensionAndRestartPersistence() throws Exception {
        Path pluginRoot = tempDir.resolve("plugin");
        Path contentsRoot = pluginRoot.resolve("contents");
        writeDemoContentPack(contentsRoot.resolve("demo_end"));
        writeSeasonalExtensionPack(contentsRoot.resolve("seasonal_addon"));

        Path dbPath = pluginRoot.resolve("dragonraid.db");
        ConfigManager configManager = new ConfigManager(
                pluginRoot,
                name -> EndToEndProgressionIntegrationTest.class.getClassLoader().getResourceAsStream(name));
        configManager.initialize();
        MessageService messageService = new MessageService(configManager);
        messageService.reload();

        RaidScheduler directScheduler = mock(RaidScheduler.class);
        doAnswer(inv -> {
            inv.getArgument(1, Runnable.class).run();
            return mock(TaskHandle.class);
        }).when(directScheduler).runAtRegion(any(Location.class), any(Runnable.class));
        doAnswer(inv -> {
            inv.getArgument(1, Runnable.class).run();
            return mock(TaskHandle.class);
        }).when(directScheduler).runAtEntity(any(Entity.class), any(Runnable.class));
        doAnswer(inv -> {
            inv.getArgument(1, Runnable.class).run();
            return mock(TaskHandle.class);
        }).when(directScheduler).runAtEntity(any(Entity.class), any(Runnable.class), any(Runnable.class));
        when(directScheduler.asyncExecutor()).thenReturn(Runnable::run);

        World overworld = mock(World.class);
        when(overworld.getEnvironment()).thenReturn(World.Environment.NORMAL);
        when(overworld.getName()).thenReturn("world");

        World endWorld = mock(World.class);
        when(endWorld.getEnvironment()).thenReturn(World.Environment.THE_END);
        when(endWorld.getName()).thenReturn("world_the_end");
        DragonBattle dragonBattle = mock(DragonBattle.class);
        EnderDragon liveVanillaDragon = mock(EnderDragon.class);
        when(endWorld.getEnderDragonBattle()).thenReturn(dragonBattle);
        when(dragonBattle.getEnderDragon()).thenReturn(liveVanillaDragon);
        when(dragonBattle.hasBeenPreviouslyKilled()).thenReturn(false);
        AtomicInteger gateways = new AtomicInteger(0);
        when(dragonBattle.getGatewayCount()).thenAnswer(inv -> gateways.get());
        when(dragonBattle.spawnNewGateway()).thenAnswer(inv -> {
            gateways.incrementAndGet();
            return true;
        });

        UUID aliceId = UUID.randomUUID();
        Player alice = mock(Player.class);
        when(alice.getUniqueId()).thenReturn(aliceId);
        when(alice.getName()).thenReturn("Alice");
        when(alice.isOnline()).thenReturn(true);
        when(alice.locale()).thenReturn(Locale.JAPAN);
        when(alice.getWorld()).thenReturn(overworld);
        when(alice.getLocation()).thenReturn(new Location(overworld, 0, 64, 0, 0f, 0f));

        AtomicInteger toastCount = new AtomicInteger();
        AtomicInteger scriptBonusCount = new AtomicInteger();
        AtomicLong clock = new AtomicLong(100_000L);

        try (SqlitePlayerDataRepository repo = new SqlitePlayerDataRepository(dbPath, 2, Runnable::run)) {
            RaidServiceRegistry registry = new DefaultRaidServiceRegistry();
            DefaultScriptHookRegistry scriptHooks = new DefaultScriptHookRegistry();
            scriptHooks.registerAction("on_normal_clear_bonus", ctx -> scriptBonusCount.incrementAndGet());

            DefaultContentUnlockService unlockService = new DefaultContentUnlockService(repo, clock::get, Logger.getLogger("e2e"));
            DefaultContentFinderService finderService = new DefaultContentFinderService(unlockService);
            VanillaProgressionService vanillaService = new FoliaVanillaProgressionService(
                    configManager, directScheduler, name -> "world_the_end".equals(name) ? endWorld : null, Logger.getLogger("e2e"));

            ContentUnlockNotifier notifier = new PacketEventsContentUnlockNotifier(
                    messageService,
                    directScheduler,
                    id -> aliceId.equals(id) ? alice : null,
                    (player, title, desc, icon, cid) -> toastCount.incrementAndGet(),
                    Logger.getLogger("e2e")
            );
            RewardCurrencySink currencySink = (uid, name, currencyId, amount) ->
                    repo.addCurrency(uid, name, currencyId, amount).thenApply(PointTransactionResult::success);
            FoliaRewardItemDelivery itemDelivery = new FoliaRewardItemDelivery(
                    directScheduler, id -> aliceId.equals(id) ? alice : null, null, Logger.getLogger("e2e"));

            AtomicReference<RewardDispatchListener> dispatcherRef = new AtomicReference<>();
            DefaultRaidAchievementService achievementService = new DefaultRaidAchievementService(
                    repo,
                    scriptHooks,
                    registry,
                    event -> {
                        RewardDispatchListener dispatcher = dispatcherRef.get();
                        if (dispatcher != null) {
                            dispatcher.dispatchAchievementRewards(event).join();
                        }
                    },
                    clock::get,
                    Logger.getLogger("e2e")
            );
            DefaultRewardApplier applier = new DefaultRewardApplier(
                    unlockService, finderService, notifier, currencySink, itemDelivery,
                    scriptHooks, repo, registry, clock::get, Logger.getLogger("e2e"));
            DefaultRaidRewardService rewardService = new DefaultRaidRewardService(
                    new PureRewardTableEvaluator(scriptHooks, registry), applier);

            AtomicReference<DefaultContentPackLoader> packLoaderRef = new AtomicReference<>();
            RewardDispatchListener rewardDispatcher = new RewardDispatchListener(
                    rewardService,
                    finderService,
                    achievementService,
                    unlockService,
                    repo,
                    clock::get,
                    Logger.getLogger("e2e"),
                    () -> {
                        DefaultContentPackLoader l = packLoaderRef.get();
                        return l != null ? l.catalogSnapshot() : net.sabafly.dragonraid.api.pack.ContentPackCatalogSnapshot.empty();
                    });
            dispatcherRef.set(rewardDispatcher);

            DefaultVanillaEndProgressionPolicy policy = new DefaultVanillaEndProgressionPolicy(
                    repo, unlockService, achievementService, vanillaService, scriptHooks, registry, clock::get, Logger.getLogger("e2e"));

            registry.register(RaidAchievementService.class, achievementService);
            registry.register(RaidRewardService.class, rewardService);
            registry.register(ContentUnlockService.class, unlockService);
            registry.register(ContentFinderService.class, finderService);
            registry.register(VanillaEndProgressionPolicy.class, policy);
            registry.register(VanillaProgressionService.class, vanillaService);
            registry.register(ScriptHookRegistry.class, scriptHooks);

            DefaultContentPackLoader packLoader = new DefaultContentPackLoader(
                    contentsRoot, finderService, achievementService, rewardService, policy, Logger.getLogger("e2e"));
            packLoaderRef.set(packLoader);
            ContentPackLoadReport report = packLoader.reload();
            assertEquals(2, report.loadedPacks().size());
            assertEquals(2, report.totalRewardTables());
            assertEquals(1, report.totalRewardExtensions());

            VanillaEndPortalListener portalListener = new VanillaEndPortalListener(
                    achievementService, policy, mock(RaidInstanceManager.class), messageService, clock::get, Logger.getLogger("e2e"));

            // Pre-portal contact: Alice cannot request Content Finder entry nor enter vanilla End
            assertFalse(finderService.requestEntry(aliceId, "ender_dragon_normal"));

            // Step 1: Alice touches End Portal -> denied vanilla End, unlocks achievement -> caller-specified reward table -> unlock + toast
            EntityPortalEnterEvent firstTouch = new EntityPortalEnterEvent(alice, new Location(overworld, 0, 64, 0), PortalType.ENDER);
            portalListener.onEntityPortalEnter(firstTouch);
            assertTrue(firstTouch.isCancelled());
            assertTrue(achievementService.hasUnlocked(aliceId, "touch_end_portal"));
            assertTrue(unlockService.isUnlocked(aliceId, "ender_dragon_normal"));
            assertEquals(1, toastCount.get());
            verify(alice, times(1)).showTitle(any(Title.class));
            assertTrue(finderService.requestEntry(aliceId, "ender_dragon_normal"));

            // Step 2: Touching portal again does NOT duplicate achievement or unlock toast
            clock.addAndGet(5000L);
            EntityPortalEnterEvent secondTouch = new EntityPortalEnterEvent(alice, new Location(overworld, 0, 64, 0), PortalType.ENDER);
            portalListener.onEntityPortalEnter(secondTouch);
            assertTrue(secondTouch.isCancelled());
            assertEquals(1, toastCount.get());

            // Step 3: 1st Clear Normal encounter -> SERVER_FIRST + firstClear(15) + base(10) + bossDamage scaled(floor(1500/200)=7)
            //         + seasonal_addon injected currency(3 festival_coin) + claim-key sf bonus + DragonBattle sync
            EncounterResultSnapshot victory1 = new EncounterResultSnapshot(
                    "ender_dragon_normal",
                    EncounterDifficulty.NORMAL,
                    EncounterOutcome.VICTORY,
                    120_000L,
                    true,
                    1,
                    Map.of(aliceId, new ParticipantCombatMetrics(
                            aliceId, "Alice", Map.of("ancient_dragon", 1500.0), Map.of(EnemyRole.PRIMARY_BOSS, 1500.0),
                            Map.of(), 0, 0, 0))
            );
            achievementService.evaluateEncounterCompleted(victory1).join();
            RewardApplyResult firstApply = rewardDispatcher.dispatchEncounterRewards(victory1).join().get(0);
            policy.onEncounterClearedAsync(victory1, true).join();

            assertTrue(achievementService.hasUnlocked(aliceId, "server_first_normal"));
            assertEquals(32L, repo.getCurrencyBalance(aliceId, "dragon_token").join());
            assertEquals(3L, repo.getCurrencyBalance(aliceId, "festival_coin").join());
            assertEquals(1, scriptBonusCount.get());
            assertEquals(Set.of("ender_dragon_normal_sf_claim"), firstApply.newlyClaimedKeys());
            assertEquals(1L, repo.getCurrencyBalance(aliceId, "sovereign_crest").join());
            assertTrue(repo.loadClaimedRewards(aliceId).join().contains("ender_dragon_normal_sf_claim"));
            verify(liveVanillaDragon, times(1)).remove();
            verify(dragonBattle, times(1)).generateEndPortal(true);
            verify(dragonBattle, times(1)).setPreviouslyKilled(true);
            verify(dragonBattle, times(1)).spawnNewGateway();

            // Step 4: 2nd Clear -> base(10) + damage(7) = +17 dragon_token (49 total), +3 festival_coin (6 total), claim-key pool skipped!
            EncounterResultSnapshot victory2 = new EncounterResultSnapshot(
                    "ender_dragon_normal",
                    EncounterDifficulty.NORMAL,
                    EncounterOutcome.VICTORY,
                    115_000L,
                    false,
                    1,
                    Map.of(aliceId, new ParticipantCombatMetrics(
                            aliceId, "Alice", Map.of("ancient_dragon", 1500.0), Map.of(EnemyRole.PRIMARY_BOSS, 1500.0),
                            Map.of(), 0, 0, 0))
            );
            RewardApplyResult secondApply = rewardDispatcher.dispatchEncounterRewards(victory2).join().get(0);
            assertEquals(49L, repo.getCurrencyBalance(aliceId, "dragon_token").join());
            assertEquals(6L, repo.getCurrencyBalance(aliceId, "festival_coin").join());
            assertEquals(2, scriptBonusCount.get());
            assertEquals(1L, repo.getCurrencyBalance(aliceId, "sovereign_crest").join());
            assertTrue(secondApply.newlyClaimedKeys().isEmpty());

            // Pure dry-run evaluation with seed does not mutate currency or execute scripts
            RewardContext dryCtx = RewardContext.forEncounterParticipant(
                    victory2,
                    victory2.participantMetrics().get(aliceId),
                    false,
                    false,
                    achievementService.unlockedFor(aliceId),
                    unlockService.unlockedContents(aliceId),
                    repo.loadClaimedRewards(aliceId).join()
            );
            RewardBundle dry1 = rewardService.evaluate("clear_normal_rewards", dryCtx, new Random(42L));
            RewardBundle dry2 = rewardService.evaluate("clear_normal_rewards", dryCtx, new Random(42L));
            assertEquals(dry1, dry2);
            assertEquals(17L, dry1.totalCurrencies().get("dragon_token"));
            assertEquals(3L, dry1.totalCurrencies().get("festival_coin"));
            assertEquals(49L, repo.getCurrencyBalance(aliceId, "dragon_token").join());
            assertEquals(2, scriptBonusCount.get());

            // Step 5: Vanilla End portal entry is now permitted
            PlayerPortalEvent portalAfterClear = new PlayerPortalEvent(
                    alice, new Location(overworld, 0, 64, 0), new Location(endWorld, 100, 50, 0), TeleportCause.END_PORTAL);
            portalListener.onPlayerPortal(portalAfterClear);
            assertFalse(portalAfterClear.isCancelled());
        }

        // Step 6: Simulated server restart with fresh services & reopened SQLite DB
        UUID bobId = UUID.randomUUID();
        try (SqlitePlayerDataRepository reopened = new SqlitePlayerDataRepository(dbPath, 2, Runnable::run)) {
            DefaultContentUnlockService unlock2 = new DefaultContentUnlockService(reopened, clock::get, Logger.getLogger("e2e2"));
            DefaultContentFinderService finder2 = new DefaultContentFinderService(unlock2);
            DefaultRaidAchievementService ach2 = new DefaultRaidAchievementService(
                    reopened, null, null, ev -> {}, clock::get, Logger.getLogger("e2e2"));
            DefaultRaidRewardService rew2 = new DefaultRaidRewardService(
                    new PureRewardTableEvaluator(),
                    new DefaultRewardApplier(unlock2, finder2, null, null, null, null, reopened, null, clock::get, Logger.getLogger("e2e2")));
            DefaultVanillaEndProgressionPolicy policy2 = new DefaultVanillaEndProgressionPolicy(
                    reopened, unlock2, ach2, null, null, null, clock::get, Logger.getLogger("e2e2"));

            DefaultContentPackLoader packLoader2 = new DefaultContentPackLoader(
                    contentsRoot, finder2, ach2, rew2, policy2, Logger.getLogger("e2e2"));
            packLoader2.reload();
            policy2.loadServerMilestones().join();
            ach2.loadPlayer(aliceId).join();
            unlock2.loadPlayer(aliceId).join();

            assertTrue(ach2.hasUnlocked(aliceId, "touch_end_portal"));
            assertTrue(ach2.hasUnlocked(aliceId, "server_first_normal"));
            assertTrue(unlock2.isUnlocked(aliceId, "ender_dragon_normal"));
            assertTrue(finder2.requestEntry(aliceId, "ender_dragon_normal"));
            assertTrue(policy2.canEnterVanillaEnd(aliceId));
            assertEquals(49L, reopened.getCurrencyBalance(aliceId, "dragon_token").join());
            assertEquals(6L, reopened.getCurrencyBalance(aliceId, "festival_coin").join());
            assertTrue(reopened.loadClaimedRewards(aliceId).join().contains("ender_dragon_normal_sf_claim"));

            // Bob clearing later does NOT trigger SERVER_FIRST achievement again
            EncounterResultSnapshot bobClear = new EncounterResultSnapshot(
                    "ender_dragon_normal",
                    EncounterDifficulty.NORMAL,
                    EncounterOutcome.VICTORY,
                    110_000L,
                    true,
                    1,
                    Map.of(bobId, new ParticipantCombatMetrics(
                            bobId, "Bob", Map.of(), Map.of(EnemyRole.PRIMARY_BOSS, 1200.0), Map.of(), 0, 0, 0))
            );
            ach2.evaluateEncounterCompleted(bobClear).join();
            assertFalse(ach2.hasUnlocked(bobId, "server_first_normal"));
        }
    }

    private static void writeDemoContentPack(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("contents"));
        Files.createDirectories(dir.resolve("achievements"));
        Files.createDirectories(dir.resolve("rewards"));
        Files.createDirectories(dir.resolve("progression"));

        Files.writeString(dir.resolve("pack.yml"), """
                id: demo_end
                name: "Demo End Progression"
                version: "1.0.0"
                enabled: true
                priority: 10
                """);
        Files.writeString(dir.resolve("contents/normal.yml"), """
                contents:
                  - id: ender_dragon_normal
                    display-name-key: boss.normal.name
                    blueprint-id: ender_dragon_normal
                    required-unlock-key: ender_dragon_normal
                    min-players: 1
                    max-players: 8
                    min-average-item-level: 0
                    clear-rewards:
                      - clear_normal_rewards
                """);
        Files.writeString(dir.resolve("achievements/progression.yml"), """
                achievements:
                  - id: touch_end_portal
                    title-key: ach.touch.title
                    description-key: ach.touch.desc
                    icon: ENDER_EYE
                    rewards:
                      - unlock_normal_on_portal
                    triggers:
                      - type: END_PORTAL_TOUCH
                  - id: server_first_normal
                    title-key: ach.sf.title
                    description-key: ach.sf.desc
                    icon: DRAGON_HEAD
                    triggers:
                      - type: SERVER_FIRST
                        encounter-id: ender_dragon_normal
                """);
        Files.writeString(dir.resolve("rewards/progression.yml"), """
                reward-tables:
                  - id: unlock_normal_on_portal
                    pools:
                      - id: unlock_pool
                        mode: ALL
                        rolls: 1
                        entries:
                          - type: UNLOCK_CONTENT
                            target: ender_dragon_normal
                            notify: true
                            icon: ENDER_EYE
                  - id: clear_normal_rewards
                    pools:
                      - id: clear_base
                        mode: ALL
                        rolls: 1
                        entries:
                          - type: CURRENCY
                            target: dragon_token
                            amount: 10
                            functions:
                              - type: ADD_AMOUNT
                                amount: 15
                                conditions:
                                  - type: FIRST_CLEAR
                              - type: ADD_METRIC_SCALED
                                metric: BOSS_DAMAGE
                                step: 200
                                per-step: 1
                                max: 10
                          - type: SCRIPT_HOOK
                            target: on_normal_clear_bonus
                      - id: sf_bonus_pool
                        mode: ALL
                        rolls: 1
                        claim-key: ender_dragon_normal_sf_claim
                        conditions:
                          - type: SERVER_FIRST
                        entries:
                          - type: CURRENCY
                            target: sovereign_crest
                            amount: 1
                """);
        Files.writeString(dir.resolve("progression/end.yml"), """
                rules:
                  - id: vanilla_end_server_first_gate
                    priority: 100
                    entry-gate:
                      mode: REQUIRE_SERVER_FIRST
                      target: ender_dragon_normal
                      denial-message-key: progression.end-locked-server-first
                      allow-bypass-permission: true
                      push-back-on-deny: true
                    clear-hooks:
                      - encounter-id: ender_dragon_normal
                        difficulty: NORMAL
                        server-first-only: true
                        sync-dragon-battle-defeated: true
                        generate-exit-portal: true
                        remove-live-vanilla-dragon: true
                        spawn-gateway-count: 1
                        max-gateways: 20
                """);
    }

    private static void writeSeasonalExtensionPack(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("rewards"));
        Files.writeString(dir.resolve("pack.yml"), """
                id: seasonal_addon
                name: "Seasonal Bonus Extension"
                version: "1.0.0"
                enabled: true
                priority: 20
                """);
        Files.writeString(dir.resolve("rewards/extension.yml"), """
                extensions:
                  - id: seasonal_festival_injection
                    inject-into: clear_normal_rewards
                    priority: 50
                    optional: false
                    pools:
                      - id: festival_bonus
                        mode: ALL
                        rolls: 1
                        entries:
                          - type: CURRENCY
                            target: festival_coin
                            amount: 3
                """);
    }
}
