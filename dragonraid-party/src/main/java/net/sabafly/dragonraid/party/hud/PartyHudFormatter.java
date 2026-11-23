package net.sabafly.dragonraid.party.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.sabafly.dragonraid.api.party.PartyMemberCombatState;
import net.sabafly.dragonraid.api.party.PartyMemberVitals;
import net.sabafly.dragonraid.api.party.PartyRoleProvider;
import net.sabafly.dragonraid.api.party.PartyRoleTag;
import net.sabafly.dragonraid.api.party.RaidParty;

/**
 * Pure Adventure component formatter rendering FF14-style party sidebar title and rows
 * (leader badge, optional role tag, HP bar, shield guard indicator, downed/dead/offline states,
 * and shared encounter revives footer).
 */
public final class PartyHudFormatter {

    public static final int MAX_SIDEBAR_LINES = 15;
    private static final int HP_BAR_SEGMENTS = 5;

    private PartyHudFormatter() {
    }

    public static Component formatTitle(RaidParty party) {
        Objects.requireNonNull(party, "party");
        return Component.text()
                .append(Component.text("✦ ", NamedTextColor.LIGHT_PURPLE))
                .append(Component.text("PARTY", NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text(" (" + party.size() + ")", NamedTextColor.GRAY))
                .build();
    }

    public static List<Component> formatLines(
            RaidParty party,
            Map<UUID, PartyMemberVitals> vitalsByMember,
            Set<UUID> disconnectedIds,
            PartyRoleProvider roleProvider,
            int maxMembersShown) {
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(vitalsByMember, "vitalsByMember");
        Objects.requireNonNull(disconnectedIds, "disconnectedIds");
        Objects.requireNonNull(roleProvider, "roleProvider");

        List<UUID> ordered = orderLeaderFirst(party);
        boolean inCombat = false;
        int remainingRevives = 0;
        for (UUID memberId : ordered) {
            PartyMemberVitals vitals = vitalsByMember.get(memberId);
            if (vitals != null && vitals.combatState() != PartyMemberCombatState.OUT_OF_COMBAT
                    && vitals.combatState() != PartyMemberCombatState.OFFLINE) {
                inCombat = true;
                remainingRevives = Math.max(remainingRevives, vitals.remainingRevives());
            }
        }

        int reservedFooterLines = inCombat ? 1 : 0;
        int memberLimit = Math.max(1, Math.min(maxMembersShown, MAX_SIDEBAR_LINES - reservedFooterLines));
        boolean truncated = ordered.size() > memberLimit;
        int displayedCount = truncated ? Math.max(1, memberLimit - 1) : ordered.size();

        List<Component> lines = new ArrayList<>(displayedCount + (truncated ? 1 : 0) + reservedFooterLines);
        for (int i = 0; i < displayedCount; i++) {
            UUID memberId = ordered.get(i);
            boolean isLeader = memberId.equals(party.leaderId());
            boolean disconnected = disconnectedIds.contains(memberId);
            PartyMemberVitals vitals = vitalsByMember.getOrDefault(
                    memberId,
                    new PartyMemberVitals(
                            memberId,
                            memberId.toString().substring(0, 8),
                            !disconnected,
                            20.0,
                            20.0,
                            false,
                            disconnected ? PartyMemberCombatState.OFFLINE : PartyMemberCombatState.OUT_OF_COMBAT,
                            0
                    )
            );
            Optional<PartyRoleTag> roleTag = roleProvider.roleFor(memberId);
            lines.add(formatMemberRow(vitals, isLeader, disconnected, roleTag.orElse(null)));
        }

        if (truncated) {
            int hiddenCount = ordered.size() - displayedCount;
            lines.add(Component.text("  ... +" + hiddenCount + " more", NamedTextColor.GRAY));
        }

        if (inCombat) {
            lines.add(Component.text()
                    .append(Component.text("✦ 蘇生残: ", NamedTextColor.AQUA))
                    .append(Component.text(String.valueOf(remainingRevives),
                            remainingRevives > 0 ? NamedTextColor.YELLOW : NamedTextColor.RED,
                            TextDecoration.BOLD))
                    .build());
        }

        return List.copyOf(lines);
    }

    private static List<UUID> orderLeaderFirst(RaidParty party) {
        List<UUID> ordered = new ArrayList<>(party.size());
        ordered.add(party.leaderId());
        for (UUID memberId : party.memberIds()) {
            if (!memberId.equals(party.leaderId())) {
                ordered.add(memberId);
            }
        }
        return ordered;
    }

    private static Component formatMemberRow(
            PartyMemberVitals vitals,
            boolean isLeader,
            boolean disconnected,
            PartyRoleTag roleTag) {
        TextComponent.Builder builder = Component.text();
        if (roleTag != null) {
            builder.append(roleTag.badge()).append(Component.text(" "));
        }
        if (isLeader) {
            builder.append(Component.text("★", NamedTextColor.GOLD));
        } else {
            builder.append(Component.text("•", NamedTextColor.DARK_GRAY));
        }

        boolean effectivelyOffline = disconnected || !vitals.online()
                || vitals.combatState() == PartyMemberCombatState.OFFLINE;
        NamedTextColor nameColor = switch (effectivelyOffline ? PartyMemberCombatState.OFFLINE : vitals.combatState()) {
            case OFFLINE -> NamedTextColor.DARK_GRAY;
            case DOWNED -> NamedTextColor.RED;
            case DEAD_SPECTATOR -> NamedTextColor.GRAY;
            case ALIVE, OUT_OF_COMBAT -> isLeader ? NamedTextColor.WHITE : NamedTextColor.GRAY;
        };
        builder.append(Component.text(truncateName(vitals.displayName(), 12), nameColor)).append(Component.text(" "));

        if (effectivelyOffline) {
            builder.append(Component.text("⏳OFF", NamedTextColor.DARK_GRAY));
            return builder.build();
        }

        if (vitals.combatState() == PartyMemberCombatState.DOWNED) {
            builder.append(Component.text("☠DOWN", NamedTextColor.RED, TextDecoration.BOLD));
            return builder.build();
        }

        if (vitals.combatState() == PartyMemberCombatState.DEAD_SPECTATOR) {
            builder.append(Component.text("✖DEAD", NamedTextColor.DARK_RED));
            return builder.build();
        }

        builder.append(formatHpBar(vitals.health(), vitals.maxHealth()));
        int cur = (int) Math.ceil(vitals.health());
        int max = (int) Math.ceil(vitals.maxHealth());
        builder.append(Component.text(" " + cur + "/" + max, NamedTextColor.WHITE));

        if (vitals.blocking()) {
            builder.append(Component.text(" 🛡", NamedTextColor.AQUA));
        }
        return builder.build();
    }

    private static Component formatHpBar(double health, double maxHealth) {
        double ratio = maxHealth <= 0.0 ? 0.0 : Math.max(0.0, Math.min(1.0, health / maxHealth));
        int filled = (int) Math.round(ratio * HP_BAR_SEGMENTS);
        NamedTextColor barColor;
        if (ratio > 0.5) {
            barColor = NamedTextColor.GREEN;
        } else if (ratio > 0.25) {
            barColor = NamedTextColor.YELLOW;
        } else {
            barColor = NamedTextColor.RED;
        }
        String filledPart = "■".repeat(Math.max(0, Math.min(HP_BAR_SEGMENTS, filled)));
        String emptyPart = "□".repeat(Math.max(0, HP_BAR_SEGMENTS - filled));
        return Component.text()
                .append(Component.text(filledPart, barColor))
                .append(Component.text(emptyPart, NamedTextColor.DARK_GRAY))
                .build();
    }

    private static String truncateName(String name, int maxLen) {
        if (name.length() <= maxLen) {
            return name;
        }
        return name.substring(0, maxLen - 1) + "…";
    }
}
