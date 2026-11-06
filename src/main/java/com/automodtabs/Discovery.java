package com.automodtabs;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Scans the item registry and groups every registered item by the namespace
 * of its registry id.
 *
 * <p>The scan runs once, inside NeoForge's {@code RegisterEvent} for the
 * creative-mode-tab registry, and - whenever the item registry has grown -
 * again lazily on population ({@link #ensureCurrent()}), so items registered
 * after startup (e.g. by Fabric-style mods on the compatibility layer) are
 * still picked up. Tab contents are derived from the <i>current</i> config on
 * every rebuild, which is what makes live config edits apply without a restart.
 *
 * <p><b>Component timing.</b> Discovery stores plain {@link Item} references
 * and registry keys only. NeoForge binds an item's default data components to
 * its registry holder only once a world's registry data is loaded - the check
 * is {@code Holder.Reference#areComponentsBound()}; even the title screen has
 * runs where it is still unbound. All ItemStack construction is therefore
 * guarded by {@link #componentsBound()} and happens inside the creative tab
 * {@code displayItems} callbacks, which NeoForge only invokes after
 * registration, and again on every in-world rebuild, where components are
 * bound. Unbound states produce empty tab contents, which vanilla rebuilds as
 * soon as world data is up.
 *
 * <p><b>Two discovery paths.</b> Registered items (the constant
 * {@link #NAMESPACES} groups) and recipe outputs (see {@link RecipeIndex})
 * are kept separate: a vanilla item customized by a datapack recipe never
 * becomes a "mod item"; it is attributed to the recipe namespace instead.
 */
public final class Discovery {
    /** populateRecipe selector: every included recipe source (flat "Recipe Outputs" tab). */
    public static final String RECIPE_SOURCE_ALL = "<all>";
    /** populateRecipe selector: every recipe source that is not a loaded mod (datapack-style). */
    public static final String RECIPE_SOURCE_DATAPACKS = "<datapacks>";

    private static final Map<String, List<Item>> NAMESPACES = new HashMap<>();
    private static final Map<String, String> NAMESPACES_TITLES = new HashMap<>();
    /** Namespaces for which a dedicated {@code mod_<namespace>} tab was registered at startup (see {@link TabBuilder}). */
    private static final Set<String> TABBED_NAMESPACES = new LinkedHashSet<>();
    /** Memoised "items already shown in a built creative tab" set, keyed per rebuild cycle. */
    private static final Map<CreativeModeTab.ItemDisplayParameters, Set<Item>> SHOWN_ELSEWHERE =
        new IdentityHashMap<>();
    /** Memoised per-cycle "items a generated tab will show" set (used to avoid cross-tab duplicates and for move-out decisions). */
    private static final Map<CreativeModeTab.ItemDisplayParameters, Set<Item>> OUR_ITEMS =
        new IdentityHashMap<>();
    /** Holder probe used by {@link #componentsBound()} - registry references are static, so this is safe to cache. */
    private static Holder.Reference<Item> boundProbe;
    /** Item-registry size seen by the last scan; when it grows the scan repeats so late-registered items are picked up. */
    private static volatile int scannedRegistrySize = -1;
    /** Reentrancy guard for the "shown elsewhere" inspection, which force-builds other tabs mid-pass. */
    private static boolean inspectingElsewhere;

    private Discovery() {
    }

    // ------------------------------------------------------------------
    // Registry scan (no stacks, safe during registration)
    // ------------------------------------------------------------------

    /**
     * Builds the namespace groups from the item registry. Called at tab-registration
     * time and - when the item registry grew since the last scan - again at
     * population time, so items registered later than NeoForge's creative-mode-tab
     * {@code RegisterEvent} (e.g. by Fabric-style mods on the compatibility
     * layer) still end up in a generated tab.
     */
    public static void discover() {
        synchronized (Discovery.class) {
            NAMESPACES.clear();
            NAMESPACES_TITLES.clear();
            SHOWN_ELSEWHERE.clear();
            OUR_ITEMS.clear();
            boundProbe = null;
            scannedRegistrySize = BuiltInRegistries.ITEM.size();

            for (Item item : BuiltInRegistries.ITEM) {
                // Deliberately NO ItemStack is built here; default item components
                // are not bound at this point (see class doc). AIR is the one
                // instance known to always be unusable in tabs; a default-instance
                // that turns out empty is re-checked at populate time.
                if (item == null || item == Items.AIR) {
                    continue;
                }
                Identifier id = BuiltInRegistries.ITEM.getKey(item);
                NAMESPACES.computeIfAbsent(id.getNamespace(), ns -> new ArrayList<>()).add(item);
            }
            for (Map.Entry<String, List<Item>> e : NAMESPACES.entrySet()) {
                List<Item> list = e.getValue();
                dedupeInPlace(list);
                if (TabConfig.get().sortAlphabetically) {
                    list.sort(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).getPath()));
                }
                NAMESPACES_TITLES.put(e.getKey(), displayNameFor(e.getKey()));
            }
        }

        if (TabConfig.get().logDiscovery) {
            for (String ns : NAMESPACES.keySet()) {
                AutoModTabs.LOGGER.info("[AutoModTabs] discovered {} -> {} item(s)",
                    ns, NAMESPACES.get(ns).size());
            }
        }
    }

    /**
     * Re-runs {@link #discover()} when the item registry has more entries than
     * the last scan. Cheap (one integer comparison when unchanged); keeps
     * generated tabs current for mods that register items late.
     */
    public static void ensureCurrent() {
        if (BuiltInRegistries.ITEM.size() != scannedRegistrySize) {
            discover();
        }
    }

    private static void dedupeInPlace(List<Item> list) {
        // Mods can technically register the same Item instance under several
        // ids; keep one entry per unique item to avoid duplicate stacks in a tab.
        Set<Item> seen = new LinkedHashSet<>(list);
        list.clear();
        list.addAll(seen);
    }

    /** True when this item's default components are usable (respects the bound-components state per cycle). */
    static boolean hasUsableDefaultInstance(Item item) {
        try {
            return !item.getDefaultInstance().isEmpty();
        } catch (Throwable t) {
            return false; // unbound or broken default - survives safely as "not shown"
        }
    }

    /**
     * True once item default data components are bound to their registry
     * holders. NeoForge binds them when world registry data is loaded; before
     * that (title screen, early startup) any ItemStack construction throws.
     */
    public static boolean componentsBound() {
        Holder.Reference<Item> probe = boundProbe;
        if (probe == null) {
            probe = BuiltInRegistries.ITEM.get(Identifier.fromNamespaceAndPath("minecraft", "stone")).orElse(null);
            if (probe == null) {
                return false;
            }
            boundProbe = probe;
        }
        return probe.areComponentsBound();
    }

    // ------------------------------------------------------------------
    // Visibility filters (config only, stack-free)
    // ------------------------------------------------------------------

    /** Namespaces that hold items after applying the config filters. */
    public static Map<String, List<Item>> visibleNamespaces() {
        Map<String, List<Item>> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, List<Item>> e : NAMESPACES.entrySet()) {
            if (perModTabExcluded(e.getKey())) {
                continue; // per-mod generated-tab exclusion (0.5.0)
            }
            List<Item> filtered = filterItems(e.getValue());
            if (!filtered.isEmpty()) {
                out.put(e.getKey(), filtered);
            }
        }
        return out;
    }

    /**
     * True when the user chose (config screen, {@code excludeGeneratedTabs})
     * that this namespace should NOT get an extra generated tab - the mod is
     * otherwise still discovered and processed (unlike {@code excludeMods},
     * which ignores it entirely). Its items then stay in their original
     * locations; the recipe tabs are unaffected.
     */
    public static boolean perModTabExcluded(String namespace) {
        return TabConfig.get().excludeGeneratedTabs.contains(namespace);
    }

    /** Raw namespaces with registered items, unfiltered (for showEmptyTabs tab registered). */
    public static Set<String> allNamespaces() {
        return NAMESPACES.keySet();
    }

    /** Applies the config filters (excluded mods, excluded items, technical items). */
    private static List<Item> filterItems(Collection<Item> items) {
        TabConfig config = TabConfig.get();
        List<Item> out = new ArrayList<>(items.size());
        for (Item item : items) {
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            if (isExcluded(config, id)) {
                continue;
            }
            if (!config.includeTechnicalItems && isTechnical(config, id)) {
                continue;
            }
            out.add(item);
        }
        return out;
    }

    /**
     * Exclusion rules for one recipe entry. Customized variants are
     * attributed to their <i>recipe source</i> (datapack namespace), not to
     * the underlying item's registry namespace - a customized
     * {@code minecraft:painting} from a {@code dqc.portal} recipe is
     * dqc.portal's content and is unaffected by {@code excludeMods:
     * ["minecraft"]}. Exact item id patterns and technical filters still
     * apply; plain (uncustomised) outputs follow their item's id.
     */
    static boolean isRecipeEntryAllowed(TabConfig config, RecipeEntry entry, Identifier itemId) {
        if (!entry.customized()) {
            return !isExcludedItem(config, itemId);
        }
        if (config.excludeMods.contains(entry.source())) {
            return false;
        }
        String sourceWildcard = entry.source() + ":*";
        for (String pattern : config.excludeItems) {
            if (pattern.equalsIgnoreCase(sourceWildcard)
                || pattern.equalsIgnoreCase(entry.source())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isExcluded(TabConfig config, Identifier id) {
        if (config.excludeMods.contains(id.getNamespace())) {
            return true;
        }
        String full = id.toString().toLowerCase(Locale.ROOT);
        for (String pattern : config.excludeItems) {
            if (pattern.isEmpty()) {
                continue;
            }
            if (matchesPattern(pattern, full, id)) {
                return true;
            }
        }
        return false;
    }

    static boolean isExcludedItem(TabConfig config, Identifier id) {
        return isExcluded(config, id);
    }

    static boolean isTechnicalItem(TabConfig config, Identifier id) {
        return isTechnical(config, id);
    }

    /**
     * Matches one config pattern against an item id.
     *
     * <p>Accepted forms (matching for {@code excludeItems} is exact-with-globs;
     * for {@code technicalPatterns} also plain substrings): {@code namespace:path}
     * (exact), {@code namespace:*} (whole namespace), {@code namespace:prefix*},
     * {@code *:path}, {@code *text*} (contains). A pattern without {@code *}
     * still behaves as before: exact for {@code excludeItems}, substring for
     * {@code technicalPatterns}.
     */
    static boolean matchesPattern(String pattern, String fullId, Identifier id) {
        String p = pattern.trim().toLowerCase(Locale.ROOT);
        if (p.equals("*")) {
            return true;
        }
        if (p.contains("*")) {
            return globMatch(p, fullId.toLowerCase(Locale.ROOT))
                || globMatch(p, id.getPath().toLowerCase(Locale.ROOT))
                || globMatch(p, id.getNamespace().toLowerCase(Locale.ROOT) + ":*" + id.getPath().toLowerCase(Locale.ROOT))
                || globMatch(p, "*" + id.getNamespace().toLowerCase(Locale.ROOT) + ":" + id.getPath().toLowerCase(Locale.ROOT));
        }
        return fullId.equals(p) || fullId.equalsIgnoreCase(pattern.trim());
    }

    /** Simple {@code *}-glob: each literal segment must occur in order; first segment anchored at the start. */
    private static boolean globMatch(String pattern, String text) {
        if (!pattern.contains("*")) {
            return text.equals(pattern);
        }
        String[] parts = pattern.split("\\*", -1);
        int cursor = 0;
        boolean first = true;
        for (String part : parts) {
            if (part.isEmpty()) {
                first = false;
                continue;
            }
            int found = text.indexOf(part, cursor);
            if (found < 0) {
                return false;
            }
            if (first && found != 0) {
                return false; // pattern does not start with '*': head must match from position 0
            }
            first = false;
            cursor = found + part.length();
        }
        return pattern.endsWith("*") || cursor == text.length();
    }

    private static boolean isTechnical(TabConfig config, Identifier id) {
        String full = id.toString().toLowerCase(Locale.ROOT);
        String path = id.getPath().toLowerCase(Locale.ROOT);
        for (String pattern : config.technicalPatterns) {
            String p = pattern.trim().toLowerCase(Locale.ROOT);
            if (p.isEmpty()) {
                continue;
            }
            if (p.contains("*")) {
                if (matchesPattern(p, full, id)) {
                    return true;
                }
            } else if (p.contains(":")) {
                if (full.contains(p)) {
                    return true;
                }
            } else if (path.contains(p)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Tab titles
    // ------------------------------------------------------------------

    /**
     * The title for one namespace: the mod's display name when loaded through
     * FML, a prettified registry path otherwise. Neither source depends on
     * language files, so missing translations and odd mod metadata cannot
     * break tab titles.
     */
    public static String displayNameFor(String namespace) {
        String cached = NAMESPACES_TITLES.get(namespace);
        if (cached != null) {
            return cached;
        }
        String modName = modListDisplayName(namespace);
        return modName != null ? modName : prettyPath(namespace);
    }

    private static @Nullable String modListDisplayName(String namespace) {
        try {
            return net.neoforged.fml.ModList.get().getMods().stream()
                .filter(mod -> mod.getModId().equals(namespace))
                .map(mod -> mod.getDisplayName())
                .findFirst()
                .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Prettifies an unknown or unloaded namespace, e.g. "some_mod" -> "Some mod", "dqc.portal" -> "Dqc portal". */
    private static String prettyPath(String namespace) {
        String raw = namespace.replace('_', ' ').replace('.', ' ').trim();
        StringBuilder sb = new StringBuilder(raw.length());
        boolean newWord = true;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            sb.append(newWord && Character.isLetter(c) ? Character.toUpperCase(c) : c);
            newWord = c == ' ';
        }
        String name = sb.toString().trim();
        return name.isEmpty() ? namespace : name;
    }

    // ------------------------------------------------------------------
    // "Shown elsewhere" classification
    // ------------------------------------------------------------------

    /**
     * Every item already visible in a creative tab that has contents (vanilla
     * tabs, and tabs of other mods). Used to classify items as "hidden", i.e.
     * registered in the item registry but present in no tab.
     *
     * <p>Tabs registered after ours are triggered to build here, so their
     * items count as shown and are not duplicated by our generated groups.
     * Our own tabs are skipped to keep this non-recursive.
     */
    public static Set<Item> itemsShownElsewhere(CreativeModeTab.ItemDisplayParameters parameters) {
        Set<Item> cached = SHOWN_ELSEWHERE.get(parameters);
        if (cached != null) {
            return cached;
        }
        if (inspectingElsewhere) {
            // Another tab being inspected as we speak triggered this again
            // (e.g. through a reentrant BuildCreativeModeTabContentsEvent);
            // rather than recurse, report nothing for this call.
            return java.util.Collections.emptySet();
        }
        ensureCurrent();
        Set<Item> shown = new LinkedHashSet<>();
        inspectingElsewhere = true;
        try {
            boolean mayForceBuild = FabricCompat.canForceBuildForeignTabs();
            for (CreativeModeTab tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
                Identifier tabId = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
                if (AutoModTabs.MODID.equals(tabId.getNamespace())) {
                    continue;
                }
                try {
                    // In Fabric API environments (Fabricated-NeoForge) foreign
                    // tabs are recomputed and REPLACED by a Fabric mixin at the
                    // tail of buildContents; forcing a build from inside one of
                    // our own tab builds re-enters their callback stack mid-pass,
                    // so there we only read what the foreign tabs already show.
                    // On plain NeoForge the build is a plain re-entrant vanilla
                    // call and lets us see not-yet-built tabs.
                    if (mayForceBuild && !tab.hasAnyItems() && tab.getType() == CreativeModeTab.Type.CATEGORY) {
                        tab.buildContents(parameters);
                    }
                    for (ItemStack stack : tab.getSearchTabDisplayItems()) {
                        shown.add(stack.getItem());
                    }
                } catch (Throwable t) {
                    AutoModTabs.LOGGER.warn("[AutoModTabs] failed to inspect tab {}", tabId);
                    if (TabConfig.get().debugLogging) {
                        AutoModTabs.LOGGER.debug("[AutoModTabs] inspect failure detail", t);
                    }
                }
            }
        } finally {
            inspectingElsewhere = false;
        }
        if (SHOWN_ELSEWHERE.size() > 4) {
            SHOWN_ELSEWHERE.clear();
        }
        SHOWN_ELSEWHERE.put(parameters, shown);
        if (TabConfig.get().logDiscovery && componentsBound()) {
            AutoModTabs.LOGGER.info("[AutoModTabs] {} distinct item(s) already visible in other tabs", shown.size());
        }
        return shown;
    }

    /** True when the item is registered but appears in no built creative tab (as far as we know). */
    public static boolean isAbsentFromTabs(Item item) {
        Set<Item> shown = null;
        for (Set<Item> memo : SHOWN_ELSEWHERE.values()) {
            shown = memo;
            break;
        }
        return shown == null || !shown.contains(item);
    }

    /** Clears the per-cycle memo caches (used when config changes mid-session). */
    public static void invalidateCaches() {
        SHOWN_ELSEWHERE.clear();
        OUR_ITEMS.clear();
    }

    /** Records that a dedicated {@code mod_<namespace>} tab exists for this namespace (see {@link TabBuilder}). */
    static void markTabbed(String namespace) {
        TABBED_NAMESPACES.add(namespace);
    }

    /** True when the namespace got its own generated tab at registration (or is a datapack-style source). */
    static boolean namespaceHasOwnTab(String namespace) {
        return TABBED_NAMESPACES.contains(namespace);
    }

    // ------------------------------------------------------------------
    // Populating generated tabs (in-world only - components bound)
    // ------------------------------------------------------------------

    /**
     * Fills a generated registry tab's contents. Called by vanilla's lazy
     * content builder with the live parameters; all stack construction and
     * filtering happens here so config edits are picked up on the next
     * rebuild (forced by {@link TabRebuild}) without re-registering tabs.
     *
     * <p>Which contents each of our structural tabs gets is decided by the
     * <i>current</i> config, not the startup snapshot:
     * <ul>
     * <li>the grouped {@code all_mods} tab shows all items when
     * {@code singleGroupedTab} is on (plus all recipe-discovered content),
     * and - so it has a purpose in per-mod mode - only items of namespaces
     * that appeared after registration ("latecomers") when it is off;</li>
     * <li>a {@code mod_<namespace>} tab shows its namespace only when
     * {@code singleGroupedTab} is off.</li>
     * </ul>
     * Vanilla auto-hides a {@code CATEGORY} tab whose contents end up empty,
     * which is what makes live config switching work without dazzling the
     * player with dead tabs.
     */
    public static void populate(CreativeModeTab.ItemDisplayParameters parameters,
                                CreativeModeTab.Output output, @Nullable String namespace) {
        TabConfig config = TabConfig.get();
        if (!config.enabled) {
            return;
        }
        ensureCurrent();
        if (config.scanRecipes && RecipeIndex.hasData()) {
            RecipeIndex.ensureIntegratedServerScanned();
        }

        List<Item> items;
        if (namespace == null) {
            // Grouped tab.
            if (config.singleGroupedTab) {
                items = new ArrayList<>();
                for (Map.Entry<String, List<Item>> e : NAMESPACES.entrySet()) {
                    if (!e.getKey().equals(AutoModTabs.MODID) && perModTabExcluded(e.getKey())) {
                        continue;
                    }
                    items.addAll(filterItems(e.getValue()));
                }
            } else {
                // Per-mod mode: only namespaces that do not have their own tab.
                items = new ArrayList<>();
                for (Map.Entry<String, List<Item>> e : NAMESPACES.entrySet()) {
                    if (!e.getKey().equals(AutoModTabs.MODID) && perModTabExcluded(e.getKey())) {
                        continue;
                    }
                    items.addAll(filterItems(e.getValue()));
                }
                items = filterLatecomers(items);
            }
        } else {
            // Per-namespace tab.
            if (config.singleGroupedTab) {
                return; // grouped mode covers everything in all_mods
            }
            if (perModTabExcluded(namespace)) {
                return; // user excluded this mod from generated tabs (0.5.0)
            }
            List<Item> group = NAMESPACES.get(namespace);
            items = group == null ? List.of() : filterItems(group);
        }

        Set<Item> shownElsewhere = null;
        InclusionMode mode = config.mode();
        if (mode != InclusionMode.ALL || !config.includeRecipeOutputs) {
            shownElsewhere = itemsShownElsewhere(parameters);
        }

        FeatureFlagSet flags = parameters.enabledFeatures();
        Set<Item> emitted = new LinkedHashSet<>();
        for (Item item : items) {
            if (item == Items.AIR || emitted.contains(item)) {
                continue;
            }
            if (!item.isEnabled(flags)) {
                continue; // match vanilla's feature-gated tabs
            }
            if (shownElsewhere != null) {
                if (mode == InclusionMode.MISSING_ONLY && shownElsewhere.contains(item)) {
                    continue; // this item is already presented by another tab
                }
                if (mode == InclusionMode.HIDDEN_ONLY
                    && (shownElsewhere.contains(item) || RecipeIndex.outputItems().contains(item))) {
                    continue; // hidden-only: tab-less and uncrafted-only items
                }
            }
            if (config.hideUncraftableItems && RecipeIndex.hasData() && !RecipeIndex.outputItems().contains(item)) {
                continue;
            }
            if (!hasUsableDefaultInstance(item)) {
                continue;
            }
            try {
                ItemStack stack = item.getDefaultInstance().copy();
                if (stack.isEmpty()) {
                    continue;
                }
                if (stack.getCount() != 1) {
                    stack.setCount(1);
                }
                emitted.add(item);
                output.accept(stack, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            } catch (Throwable t) {
                if (TabConfig.get().debugLogging) {
                    AutoModTabs.LOGGER.debug("[AutoModTabs] failed to display item {} (components bound: {})",
                        BuiltInRegistries.ITEM.getKey(item), componentsBound(), t);
                }
            }
        }
    }

    /**
     * Filters out items whose namespace already has its own generated tab -
     * used by the grouped tab in per-mod mode, where it should only hold
     * "latecomer" content (namespaces that appeared after registration).
     */
    private static List<Item> filterLatecomers(List<Item> items) {
        List<Item> out = new ArrayList<>(items);
        out.removeIf(item -> namespaceHasOwnTab(BuiltInRegistries.ITEM.getKey(item).getNamespace())
            || TabConfig.get().excludeMods.contains(BuiltInRegistries.ITEM.getKey(item).getNamespace()));
        // The vanilla group may be filtered out by excludeMods already; drop any item
        // of an excluded namespace even when it was registered after startup.
        return out;
    }

    /**
     * Fills a generated recipe-source tab's contents: the discovered
     * {@link RecipeEntry} stacks of one source ({@code null} = flat fallback
     * combining every eligible source). Customized output stacks always show
     * when {@code includeRecipeOutputs} is on; plain (uncustomised) outputs
     * show only when the underlying item is not presented elsewhere - either
     * in another mod's tab or by one of our own registry tabs - so a recipe
     * scan supplements rather than duplicates.
     */
    public static void populateRecipe(CreativeModeTab.ItemDisplayParameters parameters,
                                      CreativeModeTab.Output output, @Nullable String source) {
        if (!componentsBound()) {
            return;
        }
        TabConfig config = TabConfig.get();
        if (!config.includeRecipeOutputs || !config.scanRecipes) {
            return;
        }
        // Live shape gating: all recipe-tab shapes are always registered, but
        // only the one the current config selects ("flat" vs "by source")
        // receives contents - the other shapes stay empty and vanilla hides
        // them, which is what makes groupRecipeOutputsBySource switchable live.
        if (RECIPE_SOURCE_ALL.equals(source) && config.groupRecipeOutputsBySource) {
            return;
        }
        if (RECIPE_SOURCE_DATAPACKS.equals(source) && !config.groupRecipeOutputsBySource) {
            return;
        }
        if (source != null && !RECIPE_SOURCE_ALL.equals(source) && !RECIPE_SOURCE_DATAPACKS.equals(source)
            && !config.groupRecipeOutputsBySource) {
            return;
        }
        if (config.scanRecipes && RecipeIndex.hasData()) {
            RecipeIndex.ensureIntegratedServerScanned();
        }
        Map<String, List<RecipeEntry>> sources = RecipeIndex.sources();
        List<RecipeEntry> entries = new ArrayList<>();
        for (Map.Entry<String, List<RecipeEntry>> e : sources.entrySet()) {
            String entrySource = e.getKey();
            boolean selected = source == null
                || RECIPE_SOURCE_ALL.equals(source)
                || source.equals(entrySource)
                || (RECIPE_SOURCE_DATAPACKS.equals(source) && TabConfig.get().isDatapackSource(entrySource));
            if (selected) {
                entries.addAll(e.getValue());
            }
        }
        Set<Item> shownElsewhere = itemsShownElsewhere(parameters);

        FeatureFlagSet flags = parameters.enabledFeatures();
        List<RecipeEntry> accepted = new ArrayList<>();
        List<RecipeEntry> sorted = new ArrayList<>(entries);
        if (config.sortAlphabetically) {
            sorted.sort(Comparator.comparing(entry -> BuiltInRegistries.ITEM.getKey(entry.item()).getPath()));
        }
        for (RecipeEntry entry : sorted) {
            try {
                if (!acceptRecipeEntry(config, parameters, entry, accepted, shownElsewhere, flags)) {
                    continue;
                }
                accepted.add(entry);
                ItemStack stack = entry.stack().copy(); // complete variant incl. components
                if (stack.isEmpty()) {
                    continue;
                }
                if (stack.getCount() != 1) {
                    stack.setCount(1);
                }
                output.accept(stack, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            } catch (Throwable t) {
                if (TabConfig.get().debugLogging) {
                    AutoModTabs.LOGGER.debug("[AutoModTabs] failed to display recipe stack for {} (source {})",
                        BuiltInRegistries.ITEM.getKey(entry.item()), entry.source(), t);
                }
            }
        }
    }

    /**
     * Config-and-state gate for one recipe entry, shared by the loop above and
     * its icon probe. Returns {@code true} when the entry should be added.
     * <ul>
     * <li>customized (datapack-variant) entries show whenever
     * {@code includeCustomRecipeStacks} is on;</li>
     * <li>plain (uncustomised) outputs show only when the underlying item is
     * not presented elsewhere - not in another mod's or a vanilla tab
     * ({@code shownElsewhere}) and not in one of our own registry tabs
     * (its namespace has a generated tab) - so a recipe scan supplements
     * rather than duplicates.</li>
     * </ul>
     */
    private static boolean acceptRecipeEntry(TabConfig config, CreativeModeTab.ItemDisplayParameters parameters,
                                             RecipeEntry entry, List<RecipeEntry> accepted,
                                             Set<Item> shownElsewhere, FeatureFlagSet flags) {
        Item item = entry.item();
        Identifier itemId = BuiltInRegistries.ITEM.getKey(item);
        if (item == Items.AIR) {
            return false;
        }
        boolean customized = entry.customized();
        if (customized && !config.includeCustomRecipeStacks) {
            return false;
        }
        if (!isRecipeEntryAllowed(config, entry, itemId)) {
            return false;
        }
        if (!config.includeTechnicalItems && isTechnicalItem(config, itemId)) {
            return false;
        }
        if (!item.isEnabled(flags)) {
            return false;
        }
        if (!customized) {
            if (shownElsewhere.contains(item)
                || visibleNamespaces().containsKey(itemId.getNamespace())) {
                // Plain items that another tab (or our own registry tab) shows
                // stay there - recipe discovery supplements, never duplicates.
                return false;
            }
        }
        for (RecipeEntry existing : accepted) {
            if (existing.sameVariant(entry)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Membership helpers
    // ------------------------------------------------------------------

    /** The items our generated registry tabs display in this cycle (memoised per parameters). */
    public static Set<Item> ourGeneratedItems(CreativeModeTab.ItemDisplayParameters parameters) {
        Set<Item> cached = OUR_ITEMS.get(parameters);
        if (cached != null) {
            return cached;
        }
        Set<Item> out = new LinkedHashSet<>();
        if (componentsBound()) {
            for (Map.Entry<String, List<Item>> e : visibleNamespaces().entrySet()) {
                out.addAll(e.getValue());
            }
        }
        if (OUR_ITEMS.size() > 4) {
            OUR_ITEMS.clear();
        }
        OUR_ITEMS.put(parameters, out);
        return out;
    }

    /** True when at least one non-excluded namespace holds at least one item. */
    public static boolean hasVisibleNamespaces() {
        return !visibleNamespaces().isEmpty();
    }

    /** True when a namespace's group is non-empty after the config filters. */
    public static boolean hasVisibleItems(String namespace) {
        return !filterItems(itemsFor(namespace)).isEmpty();
    }

    /**
     * An icon candidate for a namespace: the first filtered item of the group
     * (alphabetical order when sorting is on, so large stable namespaces pick
     * a representative first item).
     */
    public static @Nullable Item iconFor(String namespace) {
        List<Item> list = filterItems(itemsFor(namespace));
        return list.isEmpty() ? null : list.get(0);
    }

    // ------------------------------------------------------------------
    // Icon suppliers (lazy - invoked only when a tab is rendered)
    // ------------------------------------------------------------------

    /** Lazy icon: the chosen registry item's stack, components bound by then. */
    public static java.util.function.Supplier<ItemStack> iconProvider(@org.jspecify.annotations.Nullable String namespace) {
        return () -> {
            try {
                Item icon = namespace == null ? firstFilteredItem() : iconFor(namespace);
                if (icon != null && icon != Items.AIR && hasUsableDefaultInstance(icon)) {
                    return new ItemStack(icon);
                }
            } catch (Throwable t) {
                if (TabConfig.get().debugLogging) {
                    AutoModTabs.LOGGER.debug("[AutoModTabs] registry tab icon failed", t);
                }
            }
            return new ItemStack(Items.GLOW_INK_SAC);
        };
    }

    /** Lazy icon: the first accepted recipe-entry stack for one populateRecipe selector. */
    public static java.util.function.Supplier<ItemStack> recipeIconProvider(@org.jspecify.annotations.Nullable String source) {
        return () -> {
            try {
                for (RecipeEntry entry : RecipeIndex.allEntries()) {
                    String entrySource = entry.source();
                    boolean selected = source == null
                        || RECIPE_SOURCE_ALL.equals(source)
                        || source.equals(entrySource)
                        || (RECIPE_SOURCE_DATAPACKS.equals(source) && TabConfig.get().isDatapackSource(entrySource));
                    if (selected) {
                        return entry.stack().copy();
                    }
                }
            } catch (Throwable t) {
                if (TabConfig.get().debugLogging) {
                    AutoModTabs.LOGGER.debug("[AutoModTabs] recipe tab icon failed", t);
                }
            }
            return new ItemStack(Items.GLOW_INK_SAC);
        };
    }

    private static @Nullable Item firstFilteredItem() {
        for (Map.Entry<String, List<Item>> e : visibleNamespaces().entrySet()) {
            List<Item> filtered = e.getValue();
            if (!filtered.isEmpty()) {
                return filtered.get(0);
            }
        }
        return null;
    }

    /** The unfiltered group for one namespace (empty when unknown). */
    public static List<Item> itemsFor(String namespace) {
        List<Item> list = NAMESPACES.get(namespace);
        return list == null ? List.of() : list;
    }

    /** Helper used by the discovery report and option-style dumping. */
    public static Map<String, String> titles() {
        return NAMESPACES_TITLES;
    }

    /** Raw groups (unfiltered) for the discovery report's registered-counts section. */
    public static java.util.Collection<List<Item>> namespacesForReport() {
        return NAMESPACES.values();
    }
}
