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
        assertEquals(List.of("rew_b"), committedContents.get(1).clearRewards());
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
