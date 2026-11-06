package com.automodtabs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.RecipesReceivedEvent;
import org.jspecify.annotations.Nullable;

/**
 * Client-side recipe scanning for <b>full output stacks</b>.
 *
 * <p>NeoForge 26.x deliberately does not replicate the full server recipe
 * manager to the client; recipe maps only reach the client when a server-side
 * mod requests them, surfaced here via {@link RecipesReceivedEvent}. In single
 * player this mod additionally reads the integrated server's recipe manager
 * directly, which always holds the authoritative data (including every
 * datapack recipe active in the world).
 *
 * <p>Each output is stored as a {@link RecipeEntry}: the complete ItemStack
 * (components preserved), the <i>recipe id namespace</i> as the source
 * identifier, and whether the stack is a customized variant (data components
 * differ from the underlying item's default instance). This is how recipe-only
 * content such as the Pocket Portals datapack - which turns
 * {@code minecraft:painting} into two customized portal stacks through recipes
 * in the {@code dqc.portal} namespace - becomes visible in generated tabs.
 *
 * <p>Recipe data is a discovery aid, never a hard gate (unless
 * {@code hideUncraftableItems} asks for it in the config): items with unknown
 * obtainability stay included in the generated tabs.
 *
 * <p><b>Limitations:</b> only outputs exposed through the active recipe system
 * are found - loot tables, mob drops, commands, functions, advancements,
 * custom machine mechanics that bypass the recipe system, and dynamically
 * generated items are outside this index and are not claimed to be discovered.
 */
public final class RecipeIndex {
    /** Recipe outputs grouped by recipe-id namespace (source). */
    private static final Map<String, List<RecipeEntry>> SOURCES = new LinkedHashMap<>();
    /** Items that appear as an uncustomised recipe output, from whatever recipe data is available. */
    private static final Set<Item> OUTPUT_ITEMS = new LinkedHashSet<>();
    /** Whether any recipe data has been seen at all. */
    private static volatile boolean dataAvailable = false;
    /** Client level that the integrated server scan was run for (memo to avoid re-scanning every rebuild). */
    private static volatile @Nullable Level scannedLevel = null;

    // Diagnostics (read by the discovery report).
    private static volatile int recipesScanned = 0;
    private static volatile int modRecipeEntries = 0;
    private static volatile int datapackRecipeEntries = 0;
    private static volatile int customizedEntries = 0;
    private static volatile int duplicatesSkipped = 0;
    /** Tallied by {@link #record}; collapsed into {@link #customizedEntries} after a scan. */
    private static volatile int recordCustomizedCount = 0;

    private RecipeIndex() {
    }

    /** Recipe-output entries grouped by the recipe id namespace. Never null; possibly empty when no recipe data is known. */
    public static Map<String, List<RecipeEntry>> sources() {
        return SOURCES;
    }

    /** All entries across sources. Never null. */
    public static List<RecipeEntry> allEntries() {
        List<RecipeEntry> all = new ArrayList<>();
        for (List<RecipeEntry> group : SOURCES.values()) {
            all.addAll(group);
        }
        return all;
    }

    /** Every item known to appear as an uncustomised recipe output. Never null. */
    public static Set<Item> outputItems() {
        return OUTPUT_ITEMS;
    }

    /** True once any recipe data has been seen (empty data for plain multiplayer servers - see class docs). */
    public static boolean hasData() {
        return dataAvailable;
    }

    /** Whether this recipe source should be indexed at all under the current config. */
    public static boolean isSourceIncluded(String source) {
        TabConfig config = TabConfig.get();
        if (source.equals(AutoModTabs.MODID)) {
            return false; // our own namespace can never own recipes anyway
        }
        if (config.isDatapackSource(source) && !config.includeDatapackRecipes) {
            return false;
        }
        return !config.excludeMods.contains(source);
    }

    // Diagnostics getters for the discovery report.
    public static int recipesScanned() {
        return recipesScanned;
    }

    public static int modRecipeEntries() {
        return modRecipeEntries;
    }

    public static int datapackRecipeEntries() {
        return datapackRecipeEntries;
    }

    public static int customizedEntries() {
        return customizedEntries;
    }

    public static int duplicatesSkipped() {
        return duplicatesSkipped;
    }

    /** Fired (client main bus) when the server pushes recipe data; also with an empty map for vanilla servers. */
    public static void onRecipesReceived(RecipesReceivedEvent event) {
        RecipeMap map = event.getRecipeMap();
        if (Minecraft.getInstance().level == null) {
            return;
        }
        try {
            scan(map.values());
        } catch (Throwable t) {
            AutoModTabs.LOGGER.warn("[AutoModTabs] networked recipe scan failed", t);
        }
        // The integrated server always holds complete data in single player,
        // so refresh it now that a new datapack generation arrived.
        scannedLevel = null;
        ensureIntegratedServerScanned();
    }

    /**
     * Runs the integrated-server recipe scan once per client level (single
     * player). On multiplayer servers without a recipe-syncing mod the index
     * stays empty on purpose - we then simply never claim anything about
     * obtainability.
     */
    public static void ensureIntegratedServerScanned() {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) {
            return;
        }
        if (scannedLevel == level) {
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            // Multiplayer world: nothing authoritative to scan here.
            scannedLevel = level;
            return;
        }
        try {
            RecipeManager recipeManager = server.getRecipeManager();
            scan(recipeManager.getRecipes());            scannedLevel = level;
            if (TabConfig.get().logDiscovery) {
                logSummary();
            }
        } catch (Throwable t) {
            AutoModTabs.LOGGER.warn("[AutoModTabs] integrated server recipe scan failed", t);
        }
    }

    /** Rebuilds the whole index from one recipe collection. */
    private static void scan(Iterable<RecipeHolder<?>> recipes) {
        SOURCES.clear();
        OUTPUT_ITEMS.clear();
        int scanned = 0;
        int modEntries = 0;
        int datapackEntries = 0;
        int skipped = 0;
        recordCustomizedCount = 0;
        ContextMap context = SlotDisplayContext.fromLevel(Minecraft.getInstance().level);
        for (RecipeHolder<?> holder : recipes) {
            scanned++;
            try {
                for (RecipeDisplay display : holder.value().display()) {
                    for (ItemStack resolved : display.result().resolveForStacks(context)) {
                        if (resolved.isEmpty()) {
                            continue;
                        }
                        if (TabConfig.get().debugLogging) {
                            AutoModTabs.LOGGER.info("[AutoModTabs] recipe output {}: {}",
                                holder.id(), resolved);
                        }
                        int duplicate = record(holder.id().identifier(), resolved);
                        if (duplicate == 1) {
                            skipped++;
                        } else if (duplicate == 2) {
                            datapackEntries++;
                        } else if (duplicate == 3) {
                            modEntries++;
                        }
                    }
                }
            } catch (Throwable t) {
                // A single broken recipe from any installed mod must not take
                // down the whole index.
                AutoModTabs.LOGGER.debug("[AutoModTabs] failed to resolve output of recipe {}", holder.id(), t);
            }
        }
        int scanCustomized = recordCustomizedCount;
        recipesScanned = scanned;
        modRecipeEntries = modEntries;
        datapackRecipeEntries = datapackEntries;
        customizedEntries = scanCustomized;
        duplicatesSkipped = skipped;
        dataAvailable = scanned > 0;
        if (TabConfig.get().logDiscovery) {
            AutoModTabs.LOGGER.info(
                "[AutoModTabs] recipe scan: {} recipe(s), {} mod-namespace output(s), {} datapack-namespace output(s), {} customized variant(s), {} duplicate(s) skipped",
                scanned, modEntries, datapackEntries, scanCustomized, skipped);
        }
    }

    /**
     * Records one resolved output stack.
     *
     * @return 0 when nothing was recorded (excluded/config-off), 1 when it was
     *         a duplicate entry, 2 for a datapack-namespace entry, 3 for a
     *         mod-namespace entry (so the caller can tally both kinds without
     *         recomputing the classification).
     */
    private static int record(Identifier recipeId, ItemStack resolved) {
        TabConfig config = TabConfig.get();
        String source = recipeId.getNamespace();
        if (!isSourceIncluded(source)) {
            return 0;
        }
        Item item = resolved.getItem();
        boolean customized;
        try {
            ItemStack def = item.getDefaultInstance();
            DataComponentMap mine = resolved.getComponents();
            customized = !def.getComponents().equals(mine);
        } catch (Throwable t) {
            // Components not bound yet can only happen before world data is
            // up, which cannot be true while a live recipe manager holds data;
            // treat conservatively as uncustomised.
            customized = false;
        }
        if (!customized) {
            OUTPUT_ITEMS.add(item);
        }
        if (customized && !config.includeCustomRecipeStacks) {
            return 0; // user opted out of preserving customized variants entirely
        }
        List<RecipeEntry> group = SOURCES.computeIfAbsent(source, ns -> new ArrayList<>());
        if (config.deduplicateRecipeOutputs) {
            for (RecipeEntry existing : group) {
                if (existing.item() == item && existing.sameVariant(resolved)) {
                    return 1; // identical variant already recorded
                }
            }
            if (!customized) {
                // Same item already recorded uncustomised in this source; one
                // plain entry per item per source is enough.
                for (RecipeEntry existing : group) {
                    if (!existing.customized() && existing.item() == item) {
                        return 1;
                    }
                }
            }
        }
        ItemStack display = resolved.copy();
        display.setCount(1);
        group.add(new RecipeEntry(source, recipeId, item, display, resolved.getCount(), customized));
        if (customized) {
            recordCustomizedCount++;
        }
        return config.isDatapackSource(source) ? 2 : 3;
    }

    private static void logSummary() {
        AutoModTabs.LOGGER.info("[AutoModTabs] integrated server recipes: {} scanned, {} source namespace(s), {} distinct uncustomised output item(s)",
            recipesScanned, SOURCES.size(), OUTPUT_ITEMS.size());
    }

    /** Drops cached recipe knowledge (on disconnect). */
    static void clear() {
        SOURCES.clear();
        OUTPUT_ITEMS.clear();
        dataAvailable = false;
        scannedLevel = null;
        recipesScanned = 0;
        modRecipeEntries = 0;
        datapackRecipeEntries = 0;
        customizedEntries = 0;
        duplicatesSkipped = 0;
    }
}
