package com.automodtabs;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Runs a discovery/classification report once, a few seconds after the client
 * has joined a world. It builds every generated tab's contents (the same code
 * path the creative screen uses, but unattended) and logs one summary with:
 *
 * <ul>
 * <li>registered items discovered, items already shown in other tabs, hidden
 * registered items,</li>
 * <li>recipes scanned / recipe outputs / datapack-namespace outputs /
 * customized stacks / duplicates skipped,</li>
 * <li>excluded mod/item counts and the number of generated tabs (with empty
 * ones flagged - vanilla hides empty CATEGORY tabs by itself).</li>
 * </ul>
 *
 * <p>The report is driven by {@code TabConfig#logDiscovery} (default true) and
 * runs off world-join so tests only need to wait for logs, not inputs.
 *
 * <p>A slim title-screen variant runs once when the main menu shows - it can
 * only classify the registry, because item data components are not bound
 * until a world's registry data is loaded (no stack construction there).
 */
public final class DiscoveryReport {
    private static final int TICKS_AFTER_JOIN = 300; // ~15s - let the level, datapacks and recipe sync settle
    private static int ticksInWorld = -1;
    private static boolean ranForWorld = false;
    private static @Nullable Object lastWorld;
    private static boolean titleRan = false;

    private DiscoveryReport() {
    }

    /** Called from {@code ClientTickEvent.Post}; schedules and executes the report once per world. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            if (mc.gui.screen() != null && !titleRan && TabConfig.get().logDiscovery) {
                titleRan = true;
                try {
                    runReportTitleScreen();
                } catch (Throwable t) {
                    AutoModTabs.LOGGER.error("[AutoModTabs] title-screen discovery report failed", t);
                }
            }
            lastWorld = null;
            ranForWorld = false;
            return;
        }
        if (lastWorld != mc.level) {
            lastWorld = mc.level;
            ranForWorld = false;
            ticksInWorld = 0;
        }
        if (ranForWorld || !TabConfig.get().logDiscovery) {
            return;
        }
        ticksInWorld++;
        if (ticksInWorld < TICKS_AFTER_JOIN) {
            return;
        }
        ranForWorld = true;
        try {
            runReport();
        } catch (Throwable t) {
            AutoModTabs.LOGGER.error("[AutoModTabs] discovery report failed", t);
        }
    }

    /** Builds all tabs and logs the classification. Public so a user command can trigger it manually. */
    public static void runReport() {
        Minecraft mc = Minecraft.getInstance();
        FeatureFlagSet flags = mc.getConnection() != null
            ? mc.getConnection().enabledFeatures()
            : net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS;
        boolean hasPermissions = mc.player != null && mc.player.canUseGameMasterBlocks();
        HolderLookup.Provider lookup = mc.level.registryAccess();
        CreativeModeTab.ItemDisplayParameters parameters = new CreativeModeTab.ItemDisplayParameters(flags, hasPermissions, lookup);

        Discovery.ensureCurrent();
        RecipeIndex.ensureIntegratedServerScanned();
        Set<Item> shown = Discovery.itemsShownElsewhere(parameters);

        AutoModTabs.LOGGER.info("[AutoModTabs] === discovery report ===");
        reportCounts(shown);
        logTabBuilds(parameters);
        AutoModTabs.LOGGER.info("[AutoModTabs] === report done ===");
    }

    /** Registry-only classification from the title screen (components not bound yet, no recipe data). */
    private static void runReportTitleScreen() {
        Minecraft mc = Minecraft.getInstance();
        FeatureFlagSet flags = net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS;
        HolderLookup.Provider lookup = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        CreativeModeTab.ItemDisplayParameters parameters = new CreativeModeTab.ItemDisplayParameters(flags, false, lookup);

        AutoModTabs.LOGGER.info("[AutoModTabs] === discovery report (title screen, components-bound={}) ===",
            Discovery.componentsBound());
        AutoModTabs.LOGGER.info("[AutoModTabs] environment: {}", FabricCompat.describe());
        AutoModTabs.LOGGER.info("[AutoModTabs] registered items: {} across {} namespace(s); config base: inclusionMode={}, excludedMods={}, excludedItems={}, technicalPatterns={}",
            countRegistered(), Discovery.allNamespaces().size(),
            TabConfig.get().mode(), TabConfig.get().excludeMods.size(),
            TabConfig.get().excludeItems.size(), TabConfig.get().technicalPatterns.size());
        AutoModTabs.LOGGER.info("[AutoModTabs] === title-screen report done (in-world report follows after world join) ===");
    }

    private static int countRegistered() {
        int total = 0;
        for (java.util.List<Item> group : Discovery.namespacesForReport()) {
            total += group.size();
        }
        return total;
    }

    private static void reportCounts(Set<Item> shown) {
        TabConfig config = TabConfig.get();
        int registered = 0;
        int hidden = 0;
        int excludedMods = config.excludeMods.size();
        int excludedItems = config.excludeItems.size();
        for (java.util.Map.Entry<String, java.util.List<Item>> e : Discovery.visibleNamespaces().entrySet()) {
            registered += e.getValue().size();
            for (Item item : e.getValue()) {
                if (!shown.contains(item)) {
                    hidden++;
                }
            }
        }

        List<RecipeEntry> recipeEntries = RecipeIndex.allEntries();
        int recipeOut = recipeEntries.size();
        int customized = RecipeIndex.customizedEntries();
        int plainRecipe = 0;
        for (RecipeEntry entry : recipeEntries) {
            if (!entry.customized()) {
                plainRecipe++;
            }
        }

        AutoModTabs.LOGGER.info(
            "[AutoModTabs] items: {} registered ({} namespace(s)), {} shown in other tabs, {} hidden; exclusion config: {} mod(s), {} item(s)",
            registered, Discovery.visibleNamespaces().size(), shown.size(), hidden, excludedMods, excludedItems);
        AutoModTabs.LOGGER.info(
            "[AutoModTabs] recipes: {} scanned, {} known output(s), {} datapack-namespace output(s), {} customized stack(s), {} duplicate(s) skipped, {} uncustomised plain output(s)",
            RecipeIndex.recipesScanned(), recipeOut, RecipeIndex.datapackRecipeEntries(), customized,
            RecipeIndex.duplicatesSkipped(), plainRecipe);
        for (java.util.Map.Entry<String, java.util.List<Item>> e : Discovery.visibleNamespaces().entrySet()) {
            int nsHidden = 0;
            int nsRecipe = 0;
            for (Item item : e.getValue()) {
                if (!shown.contains(item)) {
                    nsHidden++;
                }
                if (RecipeIndex.outputItems().contains(item)) {
                    nsRecipe++;
                }
            }
            AutoModTabs.LOGGER.info("[AutoModTabs] namespace {}: {} registered, {} hidden, {} recipe output(s)",
                e.getKey(), e.getValue().size(), nsHidden, nsRecipe);
        }
        // 0.5.0: namespaces with items but no dedicated tab (items registered
        // after the creative-tab registration point, e.g. Fabric-style mods
        // through Fabricated-NeoForge). Their items only surface in the
        // grouped tab; forceGeneratedTabs gives them a dedicated tab.
        List<String> latecomers = new ArrayList<>();
        for (String ns : Discovery.visibleNamespaces().keySet()) {
            if (!Discovery.namespaceHasOwnTab(ns)) {
                latecomers.add(ns);
            }
        }
        if (!latecomers.isEmpty()) {
            AutoModTabs.LOGGER.info("[AutoModTabs] latecomer namespaces (no dedicated generated tab; grouped tab only): {}",
                String.join(", ", latecomers));
        }
    }

    private static void logTabBuilds(CreativeModeTab.ItemDisplayParameters parameters) {
        int built = 0;
        int empty = 0;
        for (CreativeModeTab tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
            Identifier id = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
            if (!AutoModTabs.MODID.equals(id.getNamespace())) {
                continue;
            }
            tab.buildContents(parameters);
            List<ItemStack> content = List.copyOf(tab.getDisplayItems());
            if (content.isEmpty()) {
                empty++;
                AutoModTabs.LOGGER.warn("[AutoModTabs] tab {} ended up empty (vanilla will hide it)", id);
            } else {
                built++;
                ItemStack first = content.get(0);
                AutoModTabs.LOGGER.info("[AutoModTabs] tab {}: {} item(s), first {} [{}]",
                    id, content.size(), BuiltInRegistries.ITEM.getKey(first.getItem()),
                    tab.getDisplayName().getString());
            }
        }
        AutoModTabs.LOGGER.info("[AutoModTabs] === {} built tab(s), {} empty ===", built, empty);
    }
}
