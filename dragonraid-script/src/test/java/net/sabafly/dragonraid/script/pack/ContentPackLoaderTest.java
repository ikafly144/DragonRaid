package net.sabafly.dragonraid.script.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import net.sabafly.dragonraid.api.achievement.AchievementDefinition;
import net.sabafly.dragonraid.api.achievement.AchievementTriggerType;
import net.sabafly.dragonraid.api.achievement.RaidAchievementService;
import net.sabafly.dragonraid.api.finder.ContentEntrySpec;
import net.sabafly.dragonraid.api.finder.ContentFinderService;
import net.sabafly.dragonraid.api.pack.ContentPackLoadReport;
import net.sabafly.dragonraid.api.progression.EndEntryGateMode;
import net.sabafly.dragonraid.api.progression.VanillaEndProgressionPolicy;
import net.sabafly.dragonraid.api.progression.VanillaProgressionRule;
import net.sabafly.dragonraid.api.reward.RaidRewardService;
import net.sabafly.dragonraid.api.reward.RewardEntryType;
import net.sabafly.dragonraid.api.reward.RewardFunctionType;
import net.sabafly.dragonraid.api.reward.RewardPoolMode;
import net.sabafly.dragonraid.api.reward.RewardTable;
import net.sabafly.dragonraid.api.reward.RewardTableExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContentPackLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsPrioritySortedEnabledPacksWithLootTablesAndExtensions() throws Exception {
        Path contentsRoot = tempDir.resolve("contents");

        writePack(contentsRoot.resolve("pack_b"), """
                id: pack_b
                name: "Pack B"
                version: "1.2.0"
                enabled: true
                priority: 20
                """, """
                contents:
                  - id: raid_b
                    display-name-key: content.raid_b
                    blueprint-id: bp_b
                    required-unlock-key: raid_b
                    unlock-policy: LEADER_ONLY
                    hidden-until-unlocked: true
                    description-key: content.raid_b.desc
                    icon: DRAGON_HEAD
                    category: raid
                    min-players: 1
                    max-players: 8
                    min-average-item-level: 100
                    clear-rewards:
                      - rew_b
                """, """
                achievements:
                  - id: ach_b
                    title-key: ach.b.title
                    description-key: ach.b.desc
                    icon: DRAGON_HEAD
                    triggers:
                      - type: SERVER_FIRST
                        encounter-id: raid_b
                        difficulty: HARD
                        max-duration-seconds: 600
                """, """
                reward-tables:
                  - id: sub_relics
                    pools:
                      - id: relic_pool
                        rolls: 1
                        entries:
                          - type: CUSTOM_ITEM
                            target: abyss_crest
                            amount: 1
                  - id: rew_b
                    pools:
                      - id: base
                        mode: ALL
                        claim-key: rew_b_sf
                        conditions:
                          - type: SERVER_FIRST
                          - type: DIFFICULTY
                            difficulty: HARD
                        entries:
                          - type: CURRENCY
                            target: dragon_token
                            amount: 30
                            functions:
                              - type: ADD_METRIC_SCALED
                                metric: BOSS_DAMAGE
                                step: 200
                                per-step: 1
                                max: 10
                          - type: TABLE_REF
                            target: sub_relics
                            weight: 1
                extensions:
                  - id: ext_on_a
                    inject-into: rew_a
                    priority: 50
                    optional: false
                    pools:
                      - id: bonus_from_b
                        rolls: 1
                        entries:
                          - type: CURRENCY
                            target: festival_token
                            amount: { min: 1, max: 3 }
                """, """
                rules:
                  - id: rule_b
                    priority: 20
                    entry-gate:
                      mode: REQUIRE_SERVER_FIRST
                      target: raid_b
                      denial-message-key: progression.end-locked-server-first
                      allow-bypass-permission: true
                      push-back-on-deny: true
                    clear-hooks:
                      - encounter-id: raid_b
                        difficulty: HARD
                        server-first-only: true
                        sync-dragon-battle-defeated: true
                        generate-exit-portal: true
                        remove-live-vanilla-dragon: true
                        spawn-gateway-count: 1
                        max-gateways: 20
                """);

        writePack(contentsRoot.resolve("pack_a"), """
                id: pack_a
                name: "Pack A"
                version: "1.0.0"
                enabled: true
                priority: 10
                """, """
                id: raid_a
                display-name-key: content.raid_a
                blueprint-id: bp_a
                required-unlock-key: raid_a
                """, """
                id: ach_a
                title-key: ach.a.title
                description-key: ach.a.desc
                rewards: rew_a
                trigger:
                  type: END_PORTAL_TOUCH
                """, """
                id: rew_a
                pools:
                  - mode: ALL
                    rolls: 1
                    entries:
                      - type: UNLOCK_CONTENT
                        target: raid_a
                        notify: true
                """, null);

        writePack(contentsRoot.resolve("pack_disabled"), """
                id: pack_disabled
                enabled: false
                priority: 1
                """, """
                id: disabled_raid
                display-name-key: x
                blueprint-id: x
                """, null, null, null);

        List<ContentEntrySpec> committedContents = new ArrayList<>();
        List<AchievementDefinition> committedAchievements = new ArrayList<>();
        List<RewardTable> committedTables = new ArrayList<>();
        List<RewardTableExtension> committedExtensions = new ArrayList<>();
        List<VanillaProgressionRule> committedRules = new ArrayList<>();

        ContentFinderService finderService = mock(ContentFinderService.class);
        org.mockito.Mockito.doAnswer(inv -> {
            committedContents.clear();
            committedContents.addAll(inv.<Collection<ContentEntrySpec>>getArgument(0));
            return null;
        }).when(finderService).replacePackContents(any());

        RaidAchievementService achievementService = mock(RaidAchievementService.class);
        org.mockito.Mockito.doAnswer(inv -> {
            committedAchievements.clear();
            committedAchievements.addAll(inv.<Collection<AchievementDefinition>>getArgument(0));
            return null;
        }).when(achievementService).replacePackDefinitions(any());

        RaidRewardService rewardService = mock(RaidRewardService.class);
        when(rewardService.staticTableIds()).thenReturn(Set.of());
        org.mockito.Mockito.doAnswer(inv -> {
            committedTables.clear();
            committedTables.addAll(inv.<Collection<RewardTable>>getArgument(0));
            committedExtensions.clear();
            committedExtensions.addAll(inv.<Collection<RewardTableExtension>>getArgument(1));
            return null;
        }).when(rewardService).replacePackTables(any(), any());

        VanillaEndProgressionPolicy progressionPolicy = mock(VanillaEndProgressionPolicy.class);
        org.mockito.Mockito.doAnswer(inv -> {
            committedRules.clear();
            committedRules.addAll(inv.<Collection<VanillaProgressionRule>>getArgument(0));
            return null;
        }).when(progressionPolicy).replacePackRules(any());

        DefaultContentPackLoader loader = new DefaultContentPackLoader(
                contentsRoot,
                finderService,
                achievementService,
                rewardService,
                progressionPolicy,
                Logger.getLogger("test")
        );

        ContentPackLoadReport report = loader.reload();
        assertEquals(2, report.loadedPacks().size());
        assertEquals("pack_a", report.loadedPacks().get(0).manifest().id());
        assertEquals("pack_b", report.loadedPacks().get(1).manifest().id());
        assertEquals(List.of("pack_disabled"), report.skippedPackIds());
        assertEquals(3, report.totalRewardTables());
        assertEquals(1, report.totalRewardExtensions());

        assertEquals(2, committedContents.size());
        ContentEntrySpec raidBSpec = committedContents.get(1);
        assertEquals(List.of("rew_b"), raidBSpec.clearRewards());
        assertEquals(net.sabafly.dragonraid.api.finder.ContentUnlockPolicy.LEADER_ONLY, raidBSpec.unlockPolicy());
        assertTrue(raidBSpec.hiddenUntilUnlocked());
        assertEquals("content.raid_b.desc", raidBSpec.descriptionKey());
        assertEquals("DRAGON_HEAD", raidBSpec.iconMaterial());
        assertEquals("raid", raidBSpec.category());
        assertEquals(net.sabafly.dragonraid.api.finder.ContentUnlockPolicy.ALL_MEMBERS, committedContents.get(0).unlockPolicy());
        assertEquals(2, committedAchievements.size());
        assertEquals(List.of("rew_a"), committedAchievements.get(0).rewards());
        assertEquals(AchievementTriggerType.END_PORTAL_TOUCH, committedAchievements.get(0).triggers().get(0).type());
        assertEquals(600_000L, committedAchievements.get(1).triggers().get(0).maxDurationMillis());

        assertEquals(3, committedTables.size());
        assertEquals(RewardEntryType.UNLOCK_CONTENT, committedTables.get(0).pools().get(0).entries().get(0).type());
        RewardTable rewB = committedTables.stream().filter(t -> t.id().equals("rew_b")).findFirst().orElseThrow();
        assertEquals(RewardPoolMode.ALL, rewB.pools().get(0).mode());
        assertEquals(RewardFunctionType.ADD_METRIC_SCALED, rewB.pools().get(0).entries().get(0).functions().get(0).type());
        assertEquals(1, committedExtensions.size());
        assertEquals("rew_a", committedExtensions.get(0).targetTableId());

        assertEquals(1, committedRules.size());
        assertEquals(EndEntryGateMode.REQUIRE_SERVER_FIRST, committedRules.get(0).entryGate().mode());
    }

    @Test
    void rejectsUnknownReferencesAndCircularTableRefsAtomically() throws Exception {
        Path contentsRoot = tempDir.resolve("contents");
        writePack(contentsRoot.resolve("valid"), """
                id: valid
                enabled: true
                """, """
                contents:
                  - id: raid_ok
                    display-name-key: x
                    blueprint-id: bp_ok
                    clear-rewards: [tbl_ok]
                """, """
                achievements:
                  - id: ach_ok
                    title-key: t
                    description-key: d
                    rewards: [tbl_ok]
                    triggers:
                      - type: END_PORTAL_TOUCH
                """, """
                reward-tables:
                  - id: tbl_ok
                    pools:
                      - rolls: 1
                        entries:
                          - type: CURRENCY
                            target: dragon_token
                            amount: 5
                extensions:
                  - id: optional_missing_ext
                    inject-into: does_not_exist
                    optional: true
                    pools:
                      - rolls: 1
                        entries:
                          - type: EMPTY
                """, null);

        ContentFinderService finderService = mock(ContentFinderService.class);
        RaidAchievementService achievementService = mock(RaidAchievementService.class);
        RaidRewardService rewardService = mock(RaidRewardService.class);
        when(rewardService.staticTableIds()).thenReturn(Set.of());
        VanillaEndProgressionPolicy progressionPolicy = mock(VanillaEndProgressionPolicy.class);

        DefaultContentPackLoader loader = new DefaultContentPackLoader(
                contentsRoot, finderService, achievementService, rewardService, progressionPolicy, Logger.getLogger("test"));
        ContentPackLoadReport initial = loader.reload();
        assertEquals(1, initial.totalRewardTables());
        verify(rewardService, org.mockito.Mockito.times(1)).replacePackTables(any(), any());

        // 1) Achievement pointing to unknown reward table
        Path badAchPack = contentsRoot.resolve("bad_ach");
        writePack(badAchPack, "id: bad_ach\nenabled: true\n", null, """
                achievements:
                  - id: ach_bad
                    title-key: t
                    description-key: d
                    rewards: [missing_table]
                    triggers:
                      - type: END_PORTAL_TOUCH
                """, null, null);
        assertThrows(ContentPackException.class, loader::reload);
        deleteRecursive(badAchPack);

        // 2) Content clear-rewards pointing to unknown reward table
        Path badContentPack = contentsRoot.resolve("bad_content");
        writePack(badContentPack, "id: bad_content\nenabled: true\n", """
                contents:
                  - id: raid_bad
                    display-name-key: x
                    blueprint-id: bp
                    clear-rewards: [missing_clear]
                """, null, null, null);
        assertThrows(ContentPackException.class, loader::reload);
        deleteRecursive(badContentPack);

        // 3) Non-optional extension targeting missing table
        Path badExtPack = contentsRoot.resolve("bad_ext");
        writePack(badExtPack, "id: bad_ext\nenabled: true\n", null, null, """
                extensions:
                  - id: req_ext
                    inject-into: missing_target
                    optional: false
                    pools:
                      - rolls: 1
                        entries:
                          - type: EMPTY
                """, null);
        assertThrows(ContentPackException.class, loader::reload);
        deleteRecursive(badExtPack);

        // 4) Circular TABLE_REF
        Path cyclePack = contentsRoot.resolve("cycle");
        writePack(cyclePack, "id: cycle\nenabled: true\n", null, null, """
                reward-tables:
                  - id: cyc_a
                    pools:
                      - rolls: 1
                        entries:
                          - type: TABLE_REF
                            target: cyc_b
                  - id: cyc_b
                    pools:
                      - rolls: 1
                        entries:
                          - type: TABLE_REF
                            target: cyc_a
                """, null);
        ContentPackException cycEx = assertThrows(ContentPackException.class, loader::reload);
        assertTrue(cycEx.getMessage().contains("Circular TABLE_REF"));

        // Previous valid snapshot stays intact and commit was never re-invoked after failures
        assertEquals(1, loader.loadedPacks().size());
        verify(rewardService, org.mockito.Mockito.times(1)).replacePackTables(any(), any());
    }

    @Test
    void reload_rejectsCircularTableRefWithStaticTable_updatesCatalogSnapshotMonotonically_andTracksPackLoadStatus(
            @TempDir Path localRoot) throws Exception {
        net.sabafly.dragonraid.api.finder.ContentFinderService localFinder =
                mock(net.sabafly.dragonraid.api.finder.ContentFinderService.class);
        net.sabafly.dragonraid.api.achievement.RaidAchievementService localAch =
                mock(net.sabafly.dragonraid.api.achievement.RaidAchievementService.class);
        net.sabafly.dragonraid.api.reward.RaidRewardService localReward =
                mock(net.sabafly.dragonraid.api.reward.RaidRewardService.class);
        net.sabafly.dragonraid.api.progression.VanillaEndProgressionPolicy localPolicy =
                mock(net.sabafly.dragonraid.api.progression.VanillaEndProgressionPolicy.class);

        net.sabafly.dragonraid.api.reward.RewardTable staticLoop =
                new net.sabafly.dragonraid.api.reward.RewardTable(
                        "static_loop",
                        List.of(net.sabafly.dragonraid.api.reward.RewardPool.weighted(
                                "sp",
                                net.sabafly.dragonraid.api.reward.RollRange.exact(1),
                                List.of(),
                                List.of(net.sabafly.dragonraid.api.reward.RewardEntrySpec.tableRef(
                                        "pack_loop", 1, 0.0, List.of(), List.of())))));
        org.mockito.Mockito.when(localReward.staticTables()).thenReturn(List.of(staticLoop));
        org.mockito.Mockito.when(localReward.staticTableIds()).thenReturn(java.util.Set.of("static_loop"));
        org.mockito.Mockito.when(localReward.staticExtensions()).thenReturn(List.of());

        DefaultContentPackLoader isolated = new DefaultContentPackLoader(
                localRoot,
                localFinder,
                localAch,
                localReward,
                localPolicy,
                java.util.logging.Logger.getLogger("isolated"));

        // #18 initial load failure marks packLoadFailed
        Path p = localRoot.resolve("p");
        writePack(p, "id: p\nenabled: [false]\n", null, null, null, null);
        assertThrows(ContentPackException.class, isolated::reload);
        verify(localPolicy, org.mockito.Mockito.atLeastOnce()).markPackLoadFailed(any());

        // #3 cycle between static table and pack table rejected before replacePackTables
        writePack(p, "id: p\nenabled: true\n", null, null, """
                reward-tables:
                  - id: pack_loop
                    pools:
                      - rolls: 1
                        entries:
                          - type: TABLE_REF
                            target: static_loop
                """, null);
        ContentPackException loopEx = assertThrows(ContentPackException.class, isolated::reload);
        assertTrue(loopEx.getMessage().contains("Circular TABLE_REF"));
        verify(localReward, org.mockito.Mockito.never()).replacePackTables(any(), any());

        // Clear static loop table before valid v1 -> generation 1 and markPackLoadSucceeded
        org.mockito.Mockito.when(localReward.staticTables()).thenReturn(List.of());
        org.mockito.Mockito.when(localReward.staticTableIds()).thenReturn(java.util.Set.of());
        java.util.concurrent.atomic.AtomicReference<java.util.Map<String, net.sabafly.dragonraid.api.reward.RewardTable>> snapshotRef =
                new java.util.concurrent.atomic.AtomicReference<>(java.util.Map.of());
        org.mockito.Mockito.doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            java.util.Collection<net.sabafly.dragonraid.api.reward.RewardTable> tbls = inv.getArgument(0);
            java.util.Map<String, net.sabafly.dragonraid.api.reward.RewardTable> map = new java.util.LinkedHashMap<>();
            for (var t : tbls) {
                map.put(t.id(), t);
            }
            snapshotRef.set(java.util.Map.copyOf(map));
            return null;
        }).when(localReward).replacePackTables(any(), any());
        org.mockito.Mockito.when(localReward.effectiveTablesSnapshot()).thenAnswer(inv -> snapshotRef.get());
        writePack(p, "id: p\nenabled: true\n", null, null, """
                reward-tables:
                  - id: t1
                    pools:
                      - rolls: 1
                        entries:
                          - type: EMPTY
                """, null);
        isolated.reload();
        verify(localPolicy, org.mockito.Mockito.atLeastOnce()).markPackLoadSucceeded();
        net.sabafly.dragonraid.api.pack.ContentPackCatalogSnapshot snap1 = isolated.catalogSnapshot();
        assertEquals(1L, snap1.generation());
        assertTrue(snap1.rewardTables().containsKey("t1"));

        // Reload v2 -> generation 2, previous snapshot remains immutable
        writePack(p, "id: p\nenabled: true\n", null, null, """
                reward-tables:
                  - id: t2
                    pools:
                      - rolls: 1
                        entries:
                          - type: EMPTY
                """, null);
        isolated.reload();
        net.sabafly.dragonraid.api.pack.ContentPackCatalogSnapshot snap2 = isolated.catalogSnapshot();
        assertEquals(2L, snap2.generation());
        assertTrue(snap2.rewardTables().containsKey("t2"));
        org.junit.jupiter.api.Assertions.assertFalse(snap1.rewardTables().containsKey("t2"));
        assertEquals(1L, snap1.generation());
    }

    @Test
    void yamlLoader_rejectsNonScalarValuesForScalarFields(@TempDir Path localRoot) throws Exception {
        // enabled: [false]
        Path p1 = localRoot.resolve("p1");
        writePack(p1, "id: p1\nenabled: [false]\n", null, null, null, null);
        assertThrows(ContentPackException.class, () -> ContentPackYamlLoader.loadPack(p1));

        // priority: [10]
        Path p2 = localRoot.resolve("p2");
        writePack(p2, "id: p2\npriority: [10]\n", null, null, null, null);
        assertThrows(ContentPackException.class, () -> ContentPackYamlLoader.loadPack(p2));

        // difficulty: [HARD]
        Path p3 = localRoot.resolve("p3");
        writePack(p3, "id: p3\nenabled: true\n", null, null, null, """
                rules:
                  - id: r1
                    clear-hooks:
                      - encounter-id: dragon
                        difficulty: [HARD]
                        sync-dragon-battle-defeated: true
                """);
        assertThrows(ContentPackException.class, () -> ContentPackYamlLoader.loadPack(p3));

        // target: [foo]
        Path p4 = localRoot.resolve("p4");
        writePack(p4, "id: p4\nenabled: true\n", null, null, """
                reward-tables:
                  - id: t1
                    pools:
                      - rolls: 1
                        entries:
                          - type: CURRENCY
                            target: [foo]
                            amount: 1
                """, null);
        assertThrows(ContentPackException.class, () -> ContentPackYamlLoader.loadPack(p4));
    }

    @Test
    void rejectsCrossEntryContentAndBlueprintCollision_andDetectsStaticPlusStagedExtensionCycle(@TempDir Path localRoot)
            throws Exception {
        net.sabafly.dragonraid.api.finder.ContentFinderService finder =
                mock(net.sabafly.dragonraid.api.finder.ContentFinderService.class);
        net.sabafly.dragonraid.api.achievement.RaidAchievementService ach =
                mock(net.sabafly.dragonraid.api.achievement.RaidAchievementService.class);
        net.sabafly.dragonraid.api.reward.RaidRewardService reward =
                mock(net.sabafly.dragonraid.api.reward.RaidRewardService.class);
        net.sabafly.dragonraid.api.progression.VanillaEndProgressionPolicy policy =
                mock(net.sabafly.dragonraid.api.progression.VanillaEndProgressionPolicy.class);

        DefaultContentPackLoader loader = new DefaultContentPackLoader(
                localRoot, finder, ach, reward, policy, Logger.getLogger("collision-test"));

        // #25: cross-entry blueprintId == another entry's contentId rejected
        Path collisionPack = localRoot.resolve("collision");
        writePack(collisionPack, "id: collision\nenabled: true\n", """
                contents:
                  - id: entry_a
                    display-name-key: k.a
                    blueprint-id: shared_key
                  - id: shared_key
                    display-name-key: k.b
                    blueprint-id: bp_b
                """, null, null, null);
        assertThrows(ContentPackException.class, loader::reload);

        deleteRecursive(collisionPack);

        // #27: static extension + staged pack extension merge forms cycle and is rejected before commit
        RewardTable staticTarget = new RewardTable("t_static", List.of(
                net.sabafly.dragonraid.api.reward.RewardPool.allOf(
                        "base", null, List.of(),
                        List.of(net.sabafly.dragonraid.api.reward.RewardEntrySpec.empty(1)))));
        RewardTableExtension staticExt = new RewardTableExtension(
                "ext_static_to_pack", "static", "t_static", 10, false,
                List.of(net.sabafly.dragonraid.api.reward.RewardPool.allOf(
                        "s_ref", null, List.of(),
                        List.of(net.sabafly.dragonraid.api.reward.RewardEntrySpec.tableRef("t_pack", 1, 0.0, List.of(), List.of())))));
        when(reward.staticTables()).thenReturn(List.of(staticTarget));
        when(reward.staticTableIds()).thenReturn(Set.of("t_static"));
        when(reward.staticExtensions()).thenReturn(List.of(staticExt));

        Path extCyclePack = localRoot.resolve("ext_cycle");
        writePack(extCyclePack, "id: ext_cycle\nenabled: true\n", null, null, """
                reward-tables:
                  - id: t_pack
                    pools:
                      - rolls: 1
                        entries:
                          - type: TABLE_REF
                            target: t_static
                """, null);
        ContentPackException cycleEx = assertThrows(ContentPackException.class, loader::reload);
        assertTrue(cycleEx.getMessage().contains("Circular TABLE_REF"));
    }

    private static void deleteRecursive(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            for (Path p : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static void writePack(
            Path dir,
            String packYml,
            String contentYml,
            String achYml,
            String rewardYml,
            String progYml) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pack.yml"), packYml);
        if (contentYml != null) {
            Files.createDirectories(dir.resolve("contents"));
            Files.writeString(dir.resolve("contents/main.yml"), contentYml);
        }
        if (achYml != null) {
            Files.createDirectories(dir.resolve("achievements"));
            Files.writeString(dir.resolve("achievements/main.yml"), achYml);
        }
        if (rewardYml != null) {
            Files.createDirectories(dir.resolve("rewards"));
            Files.writeString(dir.resolve("rewards/main.yml"), rewardYml);
        }
        if (progYml != null) {
            Files.createDirectories(dir.resolve("progression"));
            Files.writeString(dir.resolve("progression/main.yml"), progYml);
        }
    }
}
