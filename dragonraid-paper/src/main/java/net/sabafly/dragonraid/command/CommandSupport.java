package net.sabafly.dragonraid.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Collection;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Shared Brigadier suggestion and player-sender validation helpers for Paper command registrars.
 */
final class CommandSupport {

    private CommandSupport() {
    }

    static int requirePlayer(CommandSourceStack source, Consumer<CommandSender> onPlayerOnly, Consumer<Player> action) {
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player player)) {
            onPlayerOnly.accept(sender);
            return 0;
        }
        action.accept(player);
        return Command.SINGLE_SUCCESS;
    }

    static CompletableFuture<Suggestions> suggestMatching(
            Collection<String> candidates,
            SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                builder.suggest(candidate);
            }
        }
        return builder.buildFuture();
    }

    static CompletableFuture<Suggestions> suggestMatchingEscaped(
            Collection<String> candidates,
            SuggestionsBuilder builder) {
        String rawRemaining = builder.getRemaining();
        String remainingLower = rawRemaining.toLowerCase(Locale.ROOT);
        String unquotedLower = (!rawRemaining.isEmpty()
                && (rawRemaining.charAt(0) == '"' || rawRemaining.charAt(0) == '\''))
                ? remainingLower.substring(1)
                : remainingLower;
        for (String candidate : candidates) {
            String escaped = StringArgumentType.escapeIfRequired(candidate);
            if (candidate.toLowerCase(Locale.ROOT).startsWith(unquotedLower)
                    || escaped.toLowerCase(Locale.ROOT).startsWith(remainingLower)) {
                builder.suggest(escaped);
            }
        }
        return builder.buildFuture();
    }
}
