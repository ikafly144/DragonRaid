package net.sabafly.dragonraid.testharness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Installs a self-contained verification Content Pack ({@code dev_ender_dragon}) into
 * {@code plugins/DragonRaid/contents/dev_ender_dragon/} for real-server E2E testing and
 * interactive {@code runFolia} sessions.
 */
final class DevContentPackInstaller {

    static final String PACK_ID = "dev_ender_dragon";
    static final String CONTENT_PRACTICE = "dev_practice_dragon";
    static final String CONTENT_NORMAL = "dev_dragon_normal";
    static final String CONTENT_HARD = "dev_dragon_hard";
    static final String UNLOCK_KEY_NORMAL = "raid:dev_dragon_normal";
    static final String UNLOCK_KEY_HARD = "raid:dev_dragon_hard";
    static final String ACHIEVEMENT_PORTAL = "dev_portal_attunement";
    static final String ACHIEVEMENT_SERVER_FIRST = "dev_first_slayer";
    static final String REWARD_TABLE_PORTAL = "dev_portal_unlock_table";
    static final String REWARD_TABLE_NORMAL_CLEAR = "dev_normal_clear_rewards";
    static final String REWARD_TABLE_SERVER_FIRST = "dev_server_first_table";
    static final String REWARD_TABLE_HARD_CLEAR = "dev_hard_clear_rewards";
    static final String PROGRESSION_RULE_ID = "dev_end_progression_rule";

    private DevContentPackInstaller() {
    }

    static Path install(Path contentsRoot, boolean overwriteExisting) throws IOException {
        Objects.requireNonNull(contentsRoot, "contentsRoot");
        Path packDir = contentsRoot.resolve(PACK_ID);
        Path manifest = packDir.resolve("pack.yml");
        if (Files.isRegularFile(manifest) && !overwriteExisting) {
            return packDir;
        }

        writeUtf8(manifest, """
                id: dev_ender_dragon
                name: "DragonRaid Dev & E2E Verification Pack"
                version: "0.1.0"
                enabled: true
                priority: 100
                description: "Verification content pack exercising M2/M3 progression, finder, rewards, and instances."
                """);

        writeUtf8(packDir.resolve("contents/raids.yml"), """
                contents:
                  - id: dev_practice_dragon
                    display-name-key: "content.dev_practice_dragon.name"
                    blueprint-id: dragon_practice
                    required-unlock-key: ""
                    min-players: 1
                    max-players: 8
                    min-average-item-level: 0
                    clear-rewards:
                      - dev_normal_clear_rewards
                    unlock-policy: ALL_MEMBERS
                    hidden-until-unlocked: false
                    description-key: "content.dev_practice_dragon.desc"
                    icon: DRAGON_HEAD
                    category: practice

                  - id: dev_dragon_normal
                    display-name-key: "content.dev_dragon_normal.name"
                    blueprint-id: dragon_normal
                    required-unlock-key: "raid:dev_dragon_normal"
                    min-players: 1
                    max-players: 8
                    min-average-item-level: 0
                    clear-rewards:
                      - dev_normal_clear_rewards
                    unlock-policy: ALL_MEMBERS
                    hidden-until-unlocked: false
                    description-key: "content.dev_dragon_normal.desc"
                    icon: DRAGON_EGG
                    category: raid

                  - id: dev_dragon_hard
                    display-name-key: "content.dev_dragon_hard.name"
                    blueprint-id: dragon_hard
                    required-unlock-key: "raid:dev_dragon_hard"
                    min-players: 1
                    max-players: 8
                    min-average-item-level: 0
                    clear-rewards:
                      - dev_hard_clear_rewards
                    unlock-policy: ALL_MEMBERS
                    hidden-until-unlocked: true
                    description-key: "content.dev_dragon_hard.desc"
                    icon: NETHER_STAR
                    category: savage
                """);

        writeUtf8(packDir.resolve("achievements/achievements.yml"), """
                achievements:
                  - id: dev_portal_attunement
                    title-key: "ach.dev_portal_attunement.title"
                    description-key: "ach.dev_portal_attunement.desc"
                    icon: END_PORTAL_FRAME
                    hidden: false
                    rewards:
                      - dev_portal_unlock_table
                    triggers:
                      - type: END_PORTAL_TOUCH

                  - id: dev_first_slayer
                    title-key: "ach.dev_first_slayer.title"
                    description-key: "ach.dev_first_slayer.desc"
                    icon: DRAGON_HEAD
                    hidden: false
                    rewards:
                      - dev_server_first_table
                    triggers:
                      - type: SERVER_FIRST
                        encounter-id: dragon_normal
                        difficulty: NORMAL
                      - type: ENCOUNTER_CLEAR
                        encounter-id: dragon_normal
                        difficulty: NORMAL
                """);

        writeUtf8(packDir.resolve("rewards/rewards.yml"), """
                reward-tables:
                  - id: dev_portal_unlock_table
                    pools:
                      - id: unlock_normal_pool
                        mode: ALL
                        rolls: 1
                        claim-key: "dev:portal_unlock_normal"
                        entries:
                          - type: UNLOCK_CONTENT
                            target: "raid:dev_dragon_normal"
                            notify: true
                            icon: DRAGON_EGG

                  - id: dev_normal_clear_rewards
                    pools:
                      - id: unlock_hard_pool
                        mode: ALL
                        rolls: 1
                        claim-key: "dev:unlock_hard_from_normal"
                        entries:
                          - type: UNLOCK_CONTENT
                            target: "raid:dev_dragon_hard"
                            notify: true
                            icon: NETHER_STAR
                          - type: SCRIPT_HOOK
                            target: "dev_ender_dragon:on_clear"
                      - id: normal_clear_currency_pool
                        mode: ALL
                        rolls: 1
                        entries:
                          - type: CURRENCY
                            target: "dragon_token"
                            amount: 25
                          - type: VANILLA_ITEM
                            target: "DIAMOND"
                            amount: 2

                  - id: dev_server_first_table
                    pools:
                      - id: sf_bonus_pool
                        mode: ALL
                        rolls: 1
                        claim-key: "dev:sf_dragon_normal_bonus"
                        entries:
                          - type: CURRENCY
                            target: "dragon_token"
                            amount: 100

                  - id: dev_hard_clear_rewards
                    pools:
                      - id: hard_clear_currency_pool
                        mode: ALL
                        rolls: 1
                        entries:
                          - type: CURRENCY
                            target: "abyssal_scale"
                            amount: 10
                          - type: VANILLA_ITEM
                            target: "NETHERITE_INGOT"
                            amount: 1
                """);

        writeUtf8(packDir.resolve("progression.yml"), """
                rules:
                  - id: dev_end_progression_rule
                    priority: 100
                    entry-gate:
                      mode: REQUIRE_PLAYER_UNLOCK
                      target: "raid:dev_dragon_hard"
                      denial-message-key: "progression.end-entry-denied"
                      allow-bypass-permission: false
                      push-back-on-deny: true
                    clear-hooks:
                      - id: dev_normal_world_sync
                        encounter-id: dragon_normal
                        difficulty: NORMAL
                        first-clear-only: false
                        server-first-only: true
                        sync-dragon-battle-defeated: true
                        generate-exit-portal: true
                        remove-live-vanilla-dragon: true
                        spawn-gateway-count: 1
                        max-gateways: 20
                """);

        return packDir;
    }

    private static void writeUtf8(Path file, String content) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
