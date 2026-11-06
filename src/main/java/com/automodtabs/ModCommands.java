package com.automodtabs;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.jspecify.annotations.Nullable;

/**
 * Client-side {@code /automodtabs} command group for live configuration, so
 * the mod can be tuned mid-session without leaving the game:
 *
 * <ul>
 * <li>{@code /automodtabs reload} - re-reads {@code config/automodtabs.json}
 * from disk and applies everything live.</li>
 * <li>{@code /automodtabs set <key> <value>} - applies one key immediately
 * (through {@link TabConfig#applyKey}), saves the file and forces a tab
 * rebuild via {@link TabRebuild}.</li>
 * <li>{@code /automodtabs get <key>} - prints the current value of one key
 * ({@code get all} = the whole config).</li>
 * <li>{@code /automodtabs tabs} - lists the generated tabs the mod registered
 * and their live state.</li>
 * <li>{@code /automodtabs report} - writes the full discovery report (see
 * {@link DiscoveryReport#runReport()}) to the client log.</li>
 * </ul>
 *
 * <p><b>Sides.</b> Commands register through NeoForge's
 * {@code RegisterClientCommandsEvent}, so they only exist on the client and
 * never reach a server; no permissions are required because they only alter
 * the local client's own config. Every handler is wrapped so an unexpected
 * exception reports a clean failure instead of breaking the command tree.
 */
public final class ModCommands {
    private ModCommands() {
    }

    /** Builds the usage line from the {@link TabConfig} key tables. */
    public static String usage() {
        return "help | reload | reset | report | tabs | get <key> | set <key> <value>"
            + " (booleans: " + String.join(" ", TabConfig.BOOLEAN_KEYS)
            + "; lists (comma separated): " + String.join(" ", TabConfig.LIST_KEYS)
            + "; inclusionMode = " + modeNames() + ")";
    }

    private static String modeNames() {
        List<String> names = new ArrayList<>();
        for (InclusionMode mode : InclusionMode.values()) {
            names.add(mode.name());
        }
        return String.join("|", names);
    }

    public static void register(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("automodtabs")
            .then(Commands.literal("help").executes(ModCommands::runHelp))
            .then(Commands.literal("reload").executes(ModCommands::runReload))
            .then(Commands.literal("reset").executes(ModCommands::runReset))
            .then(Commands.literal("report").executes(ModCommands::runReport))
            .then(Commands.literal("tabs").executes(ModCommands::runTabs))
            .then(Commands.literal("get")
                .then(Commands.argument("key", StringArgumentType.word()).executes(ModCommands::runGet)))
            .then(Commands.literal("set")
                .then(Commands.argument("key", StringArgumentType.word())
                    .then(Commands.argument("value", StringArgumentType.string()).executes(ModCommands::runSet)))));
    }

    // ------------------------------------------------------------------

    /** Full help tree: every subcommand, every key, aligned example column. */
    private static int runHelp(CommandContext<CommandSourceStack> ctx) {
        ChatFormatting title = ChatFormatting.AQUA;
        ChatFormatting key = ChatFormatting.GRAY;
        ChatFormatting example = ChatFormatting.GREEN;
        // Chat renders in a monospaced font, so space-aligned columns line up.
        String pad = "..........................................................";

        send(ctx, "[AutoModTabs] /automodtabs - live CLIENT config command (nothing is sent to any server)", title);

        send(ctx, "commands:", key);
        send(ctx, "  " + cut("help", pad) + "list every command, key and example (this text)");
        send(ctx, "  " + cut("reload", pad) + "re-read config/automodtabs.json from disk, apply live");
        send(ctx, "  " + cut("reset", pad) + "restore every key to defaults, save, rebuild live");
        send(ctx, "  " + cut("report", pad) + "write the full discovery/recipe report to the log");
        send(ctx, "  " + cut("tabs", pad) + "list the generated tabs; empty ones are marked");
        send(ctx, "  " + cut("get <key>", pad) + "print one value ('get all' prints the whole config)");
        send(ctx, "  " + cut("set <key> <value>", pad) + "change one key (any key casing works), save, apply live");

        send(ctx, "boolean keys (set true|false, or on/off/1/0/yes/no):", key);
        send(ctx, "  " + String.join(", ", TabConfig.BOOLEAN_KEYS));
        send(ctx, "list keys (comma separated):", key);
        send(ctx, "  " + String.join(", ", TabConfig.LIST_KEYS));
        send(ctx, "inclusionMode (" + modeNames() + "): ALL = every modded item,", key);
        send(ctx, "  MISSING_ONLY = only items no other tab shows, HIDDEN_ONLY = only unreachable items;");
        send(ctx, "  legacy alias includeHiddenItems still works (true=ALL, false=MISSING_ONLY)", key);

        send(ctx, "examples (every 'set' also saves the file and rebuilds tabs immediately):", example);
        send(ctx, "  /automodtabs set enabled false");
        send(ctx, "  /automodtabs set singleGroupedTab true");
        send(ctx, "  /automodtabs set hideUncraftableItems false");
        send(ctx, "  /automodtabs set inclusionMode ALL");
        send(ctx, "  /automodtabs set excludeMods minecraft,apollib");
        send(ctx, "  /automodtabs set excludeItems 'apollib:bouncy_ball,mylib:*'");
        send(ctx, "  /automodtabs set technicalPatterns 'minecraft:debug_stick,minecraft:barrier'");
        send(ctx, "  /automodtabs set sortAlphabetically off");
        send(ctx, "  /automodtabs get all");
        send(ctx, "  /automodtabs reset");

        AutoModTabs.LOGGER.info("[AutoModTabs] help shown in chat; environment: {}", FabricCompat.describe());
        return 1;
    }

    /** First column clipped/padded so the example lines share one alignment. */
    private static String cut(String text, String dots) {
        if (text.length() >= dots.length()) {
            return text + " ";
        }
        return text + dots.substring(text.length());
    }

    private static void send(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendSuccess(() -> Component.literal("[AutoModTabs] " + message), false);
    }

    private static void send(CommandContext<CommandSourceStack> ctx, String message, ChatFormatting color) {
        ctx.getSource().sendSuccess(() -> Component.literal("[AutoModTabs] " + message).withStyle(color), false);
    }

    private static int runReload(CommandContext<CommandSourceStack> ctx) {
        TabConfig.get().load();
        applyConfigChange();
        AutoModTabs.LOGGER.info("[AutoModTabs] /automodtabs reload: config re-read from disk and applied live "
            + "(singleGroupedTab={}, inclusionMode={})", TabConfig.get().singleGroupedTab, TabConfig.get().inclusionMode);
        sendOk(ctx, "config re-read from disk and applied live (see log for details)");
        return 1;
    }

    private static int runReset(CommandContext<CommandSourceStack> ctx) {
        TabConfig.get().resetToDefaults();
        TabConfig.get().save();
        applyConfigChange();
        AutoModTabs.LOGGER.info("[AutoModTabs] /automodtabs reset: all keys restored to defaults, file saved, tabs rebuilt live "
            + "(singleGroupedTab={}, inclusionMode={})", TabConfig.get().singleGroupedTab, TabConfig.get().inclusionMode);
        sendOk(ctx, "all keys restored to defaults, config saved and applied live");
        return 1;
    }

    private static int runReport(CommandContext<CommandSourceStack> ctx) {
        try {
            DiscoveryReport.runReport();
        } catch (Throwable t) {
            AutoModTabs.LOGGER.error("[AutoModTabs] manual report failed", t);
            sendFail(ctx, "report failed: " + t);
            return 0;
        }
        sendOk(ctx, "discovery report written to the client log (latest.log)");
        return 1;
    }

    private static int runTabs(CommandContext<CommandSourceStack> ctx) {
        TabConfig config = TabConfig.get();
        Discovery.ensureCurrent();
        int built = 0;
        int empty = 0;
        for (var tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
            Identifier id = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
            if (!AutoModTabs.MODID.equals(id.getNamespace())) {
                continue;
            }
            // hasAnyItems reports the live state of the last build; a tab with
            // no contents is (or will be) hidden by vanilla.
            if (tab.hasAnyItems()) {
                built++;
            } else {
                empty++;
            }
            sendOk(ctx, id.getPath() + " [" + tab.getDisplayName().getString() + "]"
                + (tab.hasAnyItems() ? "" : " (empty - vanilla hides it)"));
        }
        sendOk(ctx, built + " tab(s) with contents, " + empty + " empty; singleGroupedTab=" + config.singleGroupedTab
            + ", groupRecipeOutputsBySource=" + config.groupRecipeOutputsBySource);
        return 1;
    }

    private static int runGet(CommandContext<CommandSourceStack> ctx) {
        String key = StringArgumentType.getString(ctx, "key");
        TabConfig config = TabConfig.get();
        if (key.equalsIgnoreCase("help")) {
            return runHelp(ctx);
        }
        if (key.equalsIgnoreCase("all")) {
            for (String booleanKey : TabConfig.BOOLEAN_KEYS) {
                sendOk(ctx, booleanKey + " = " + valueOf(booleanKey));
            }
            for (String listKey : TabConfig.LIST_KEYS) {
                sendOk(ctx, listKey + " = " + valueOf(listKey));
            }
            sendOk(ctx, "inclusionMode = " + config.inclusionMode);
            return 1;
        }
        String value = valueOf(key);
        if (value == null) {
            sendFail(ctx, "unknown config key '" + key + "'");
            return 0;
        }
        sendOk(ctx, key + " = " + value);
        return 1;
    }

    private static int runSet(CommandContext<CommandSourceStack> ctx) {
        String key = StringArgumentType.getString(ctx, "key");
        String value = StringArgumentType.getString(ctx, "value");
        TabConfig config = TabConfig.get();
        if (key.equalsIgnoreCase("all") || key.equalsIgnoreCase("help")) {
            sendFail(ctx, "usage: /automodtabs set " + usage());
            return 0;
        }
        String error = config.applyKey(key, value);
        if (error != null) {
            sendFail(ctx, error);
            AutoModTabs.LOGGER.warn("[AutoModTabs] /automodtabs set {} {} failed: {}", key, value, error);
            return 0;
        }
        config.save();
        applyConfigChange();
        AutoModTabs.LOGGER.info("[AutoModTabs] /automodtabs set {} = {} - saved and applied live", key, valueOf(key));
        sendOk(ctx, key + " = " + valueOf(key) + " (saved, applied live; tab contents rebuild now)");
        return 1;
    }

    // ------------------------------------------------------------------

    /**
     * Everything that must happen after the config changed on disk: caches of
     * the old config state die, and vanilla is nudged to rebuild every tab on
     * its next contents pass.
     */
    private static void applyConfigChange() {
        Discovery.invalidateCaches();
        TabRebuild.requestRebuild();
    }

    /** Current value string for one config key; {@code null} when unknown. */
    public static @Nullable String valueOf(String key) {
        // Canonicalise so any letter casing of a known key reads correctly -
        // the old lowercased-against-camelCase switch made every key except
        // 'enabled' read as null/false, which stuck all screen labels on OFF.
        String canonical = TabConfig.canonicalKey(key);
        if (canonical == null) {
            return null;
        }
        TabConfig config = TabConfig.get();
        switch (canonical) {
            case "inclusionMode" -> {
                return config.inclusionMode;
            }
            case "includeHiddenItems" -> {
                return String.valueOf(config.includeHiddenItems);
            }
            case "enabled" -> {
                return String.valueOf(config.enabled);
            }
            case "includeTechnicalItems" -> {
                return String.valueOf(config.includeTechnicalItems);
            }
            case "sortAlphabetically" -> {
                return String.valueOf(config.sortAlphabetically);
            }
            case "singleGroupedTab" -> {
                return String.valueOf(config.singleGroupedTab);
            }
            case "scanRecipes" -> {
                return String.valueOf(config.scanRecipes);
            }
            case "hideUncraftableItems" -> {
                return String.valueOf(config.hideUncraftableItems);
            }
            case "logDiscovery" -> {
                return String.valueOf(config.logDiscovery);
            }
            case "sortTabsAlphabetically" -> {
                return String.valueOf(config.sortTabsAlphabetically);
            }
            case "removeFromOriginalTabs" -> {
                return String.valueOf(config.removeFromOriginalTabs);
            }
            case "removeFromSearch" -> {
                return String.valueOf(config.removeFromSearch);
            }
            case "moveRecipeOutputs" -> {
                return String.valueOf(config.moveRecipeOutputs);
            }
            case "preserveOriginalTabs" -> {
                return String.valueOf(config.preserveOriginalTabs);
            }
            case "includeRecipeOutputs" -> {
                return String.valueOf(config.includeRecipeOutputs);
            }
            case "includeDatapackRecipes" -> {
                return String.valueOf(config.includeDatapackRecipes);
            }
            case "includeCustomRecipeStacks" -> {
                return String.valueOf(config.includeCustomRecipeStacks);
            }
            case "groupRecipeOutputsBySource" -> {
                return String.valueOf(config.groupRecipeOutputsBySource);
            }
            case "deduplicateRecipeOutputs" -> {
                return String.valueOf(config.deduplicateRecipeOutputs);
            }
            case "debugLogging" -> {
                return String.valueOf(config.debugLogging);
            }
            case "syncFabricCreativeTabNavigation" -> {
                return String.valueOf(config.syncFabricCreativeTabNavigation);
            }
            case "excludeMods" -> {
                return String.join(", ", config.excludeMods);
            }
            case "excludeItems" -> {
                return String.join(", ", config.excludeItems);
            }
            case "technicalPatterns" -> {
                return String.join(", ", config.technicalPatterns);
            }
            default -> {
                return null;
            }
        }
    }

    private static void sendOk(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendSuccess(() -> Component.literal("[AutoModTabs] " + message), false);
    }

    private static void sendFail(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendFailure(Component.literal("[AutoModTabs] " + message));
    }
}
