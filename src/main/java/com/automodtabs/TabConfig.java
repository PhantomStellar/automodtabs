package com.automodtabs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Client-side configuration for the automatic creative tabs.
 *
 * <p>Stored as a flat JSON file in {@code config/automodtabs.json} so it can
 * be edited while the game is open and picked up on the next tab rebuild
 * (opening the world or rebuilding tab contents triggers a new pass). Missing
 * keys get defaults, unknown keys are ignored, and each value is applied
 * individually so that one malformed entry cannot invalidate the rest of the
 * file - the mod never crashes because of a broken config.
 *
 * <p><b>Upgrade path.</b> Configs written by 0.1.0 contain the legacy boolean
 * {@code includeHiddenItems} and none of the new keys. Legacy mapping:
 *
 * <ul>
 * <li>{@code includeHiddenItems: true}  &rarr; {@code inclusionMode: "ALL"}</li>
 * <li>{@code includeHiddenItems: false} &rarr; {@code inclusionMode: "MISSING_ONLY"}</li>
 * </ul>
 *
 * <p>All existing settings keep their names and meaning; new settings are
 * only added. The legacy boolean is still written back (derived from the
 * current {@link #inclusionMode}) so older tooling keeps working.
 */
public final class TabConfig {
    // ------------------------------------------------------------------
    // Existing settings (same names and semantics as 0.1.0)
    // ------------------------------------------------------------------

    /** Enable or disable the entire mod. */
    public boolean enabled = true;
    /**
     * Legacy 0.1.0 alias of the inclusion mode. Kept in sync on save:
     * {@code true} corresponds to {@link InclusionMode#ALL}, any other mode
     * writes {@code false}. On load it is only consulted when the config has
     * no {@code inclusionMode} key. Deprecated, prefer {@link #inclusionMode}.
     */
    public boolean includeHiddenItems = true;
    /** Registry namespaces (mod ids) that never get a generated tab. */
    public List<String> excludeMods = new ArrayList<>(List.of("minecraft"));
    /** Excluded item ids, "namespace:path" for exact items, "namespace:*" excludes a whole namespace. */
    public List<String> excludeItems = new ArrayList<>();
    /**
     * Item ids whose path (or full id) contains any of these lowercase
     * substrings count as "technical". Empty by default since 0.5.0: vanilla
     * technical items stay hidden through {@code excludeMods=minecraft}, and
     * users configure specific patterns themselves. Existing configs keep
     * their configured patterns (safe migration, no entries are deleted).
     */
    public List<String> technicalPatterns = new ArrayList<>();
    /**
     * Also show technical items in the generated tabs. <b>Filter order:</b>
     * excluded mods first, then excluded items, then technical patterns -
     * {@code includeTechnicalItems} can never bypass {@code excludeMods} or
     * {@code excludeItems}.
     */
    public boolean includeTechnicalItems = false;
    /** Sort items alphabetically by their registry path instead of registry order. */
    public boolean sortAlphabetically = true;
    /**
     * false = one tab per mod namespace; true = a single tab holding all modded
     * items, ordered by namespace.
     */
    public boolean singleGroupedTab = false;
    /** Scan the active recipe manager for recipe outputs (single player reads the integrated server; multiplayer needs a recipe-syncing mod). */
    public boolean scanRecipes = true;
    /**
     * Hide items in the generated tabs that are never produced by any recipe,
     * <i>but only when recipe data is actually available</i> - items with
     * unknown obtainability always stay visible. Intended as an optional
     * "only show obtainable things" filter; with uncraftable-but-popular items
     * this removes items the user might expect, so it defaults to false.
     */
    public boolean hideUncraftableItems = false;
    /** Log a discovery/classification report on startup and world join. */
    public boolean logDiscovery = true;

    // ------------------------------------------------------------------
    // New settings in 0.2.0
    // ------------------------------------------------------------------

    /**
     * Item inclusion mode, see {@link InclusionMode}. Stored as the enum
     * name; unknown values load as {@link InclusionMode#ALL} with a warning.
     */
    public String inclusionMode = InclusionMode.ALL.name();
    /** Sort the generated tabs alphabetically by their (display) names. Tab order otherwise follows registration order, after the vanilla tabs. */
    public boolean sortTabsAlphabetically = true;
    /**
     * @deprecated As of 0.3.0 this flag does nothing any more: every structural
     * tab is registered unconditionally so live config edits work without a
     * restart, and vanilla auto-hides a tab once its contents end up empty.
     * Kept only so old config files still load cleanly.
     */
    @Deprecated
    public boolean showEmptyTabs = false;
    /**
     * Remove entries from their <i>original</i> tabs after the generated tabs
     * show them, so an item appears in the generated tab instead of being
     * duplicated. Only entries the generated tabs actually display are
     * removed; the search tab keeps removable items by default (see
     * {@link #removeFromSearch}).
     */
    public boolean removeFromOriginalTabs = false;
    /** Also remove moved entries from the search tab. Removing from search is independent of {@link #removeFromOriginalTabs}. */
    public boolean removeFromSearch = false;
    /**
     * OPTIONAL, OFF BY DEFAULT: move customized recipe output stacks (identical item
     * but different data components) into the generated tab of their recipe
     * source by <b>removing them from other tabs that show the plain/default
     * variant</b>. Matching uses the complete ItemStack including components,
     * so one customized variant never removes unrelated entries of the
     * underlying vanilla item.
     *
     * <p><b>Default: false since 0.3.1.</b> This removal mechanism was found to
     * be the way AutoModTabs could "lose" foreign grouped-tab content: with it
     * on by default, any third-party grouped tab that displayed a customized
     * stack matching a scanned recipe output (common in Fabric-mod grouped tabs
     * running through Fabricated-NeoForge, and in vanilla-style datapack
     * content) had those entries silently stripped. Enabling this remains
     * supported - removal only targets exact component matches and only when a
     * generated recipe tab actually shows the variant - but it is now an
     * explicit opt-in, and with {@code removeFromOriginalTabs}, {@code
     * removeFromSearch} and this flag all off, AutoModTabs never modifies the
     * contents of existing tabs.
     */
    public boolean moveRecipeOutputs = false;
    /**
     * Always honoured as an invariant: this mod never deletes, unregisters,
     * disables or reorders the original tabs; it only filters entry contents
     * on request. Setting it to false changes nothing (the flag documents the
     * promise and is kept for explicitness).
     */
    public boolean preserveOriginalTabs = true;
    /** Include recipe outputs in the generated recipe tabs. */
    public boolean includeRecipeOutputs = true;
    /** Also scan recipes supplied by datapacks (recipe ids whose namespace is not a loaded mod id). Without this, only recipes of loaded mods are indexed. */
    public boolean includeDatapackRecipes = true;
    /** Keep customized recipe output stacks (full data components) as distinct entries instead of deduplicating them into the underlying item's default stack. */
    public boolean includeCustomRecipeStacks = true;
    /** Group recipe outputs by the namespace of the recipe id (source tab). false = a single flat "Recipe outputs" tab. */
    public boolean groupRecipeOutputsBySource = true;
    /** Do not list the same output stack twice, even when several recipes produce it. Customized variants stay separate because their components differ. */
    public boolean deduplicateRecipeOutputs = true;
    /** Verbose per-item debug logging (off by default; {@link #logDiscovery} stays on the summary level). */
    public boolean debugLogging = false;
    /**
     * OPTIONAL, OFF BY DEFAULT, needs Fabric API: synchronise the two
     * creative-screen navigation controls that exist when Fabric API runs on
     * NeoForge (through Fabricated-NeoForge). Fabric API's screen mixin adds
     * its own page state and {@code <} / {@code >} buttons and hides every
     * tab outside its own current page, while NeoForge's patched screen has
     * its own page state and buttons - with both active, tabs can disappear
     * or look empty whenever the two indices disagree. When enabled,
     * clicking either control moves the other one to a matching page (majority
     * vote, because the two systems group tabs differently); see
     * {@link NavigationBridgeHooks} for the exact mechanism and loop safety.
     *
     * <p>Without Fabric API this does nothing and the NeoForge
     * implementation is untouched.
     */
    public boolean syncFabricCreativeTabNavigation = false;

    // ------------------------------------------------------------------
    // New settings in 0.5.0
    // ------------------------------------------------------------------

    /**
     * Mod namespaces that keep being discovered and processed but do NOT get
     * an extra generated tab ({@code mod_<namespace>} and their slice of the
     * grouped tab). Independent of {@link #excludeMods}: excluded VERSUS this
     * list skim only the generated-tab side, everything else (recipe tabs,
     * search/removal rules aimed at foreign tab contents) works as usual.
     * Configured from the "Mods" page of the config screen (per-mod toggle
     * next to the detected native-tab state); one mod id per entry, unknown
     * or unloaded ids are harmless.
     */
    public List<String> excludeGeneratedTabs = new ArrayList<>();
    /**
     * Mod namespaces forced to get a dedicated generated tab. Use for mods
     * whose items register later than the creative-tab registration point
     * (for example Fabric-style mods loaded through Fabricated-NeoForge,
     * whose items would otherwise only surface in the grouped tab) - a
     * forcing entry makes the structural tab exist at startup even though
     * its items are not registered yet. Needs a valid, loaded mod id or a
     * namespace with registered items; harmless if the mod never shows up.
     */
    public List<String> forceGeneratedTabs = new ArrayList<>();

    /**
     * Show a notice (log warning + one chat line on world join) when Fabric
     * API or a Fabric-on-NeoForge bridge is detected, explaining that it may
     * affect how this mod works depending on how the fabric loader on NeoForge
     * is implemented. Turn this off once the used loader is known not to cause
     * issues - the notice does not repeat afterwards. New in 0.5.1.
     */
    public boolean fabricLoaderWarning = true;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final TabConfig INSTANCE = new TabConfig();

    public static TabConfig get() {
        return INSTANCE;
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("automodtabs.json");
    }

    /** The active inclusion mode, parsed safely from {@link #inclusionMode}. */
    public InclusionMode mode() {
        return InclusionMode.parse(inclusionMode);
    }

    /** Changes the inclusion mode and keeps the legacy alias in sync. */
    public void setMode(InclusionMode mode) {
        this.inclusionMode = mode.name();
        this.includeHiddenItems = mode == InclusionMode.ALL;
    }

    /** True when the config file may treat this recipe source as a datapack (recipe namespace matching no loaded mod id). */
    public boolean isDatapackSource(String sourceNamespace) {
        return net.neoforged.fml.ModList.get() == null
            || net.neoforged.fml.ModList.get().getModContainerById(sourceNamespace).isEmpty();
    }

    // ------------------------------------------------------------------
    // Single-key editing (command and config screen)
    // ------------------------------------------------------------------

    /** Boolean config keys with their human-readable purpose (for help text). */
    public static final List<String> BOOLEAN_KEYS = List.of(
        "enabled", "includeTechnicalItems", "sortAlphabetically", "singleGroupedTab",
        "scanRecipes", "hideUncraftableItems", "logDiscovery", "sortTabsAlphabetically",
        "removeFromOriginalTabs", "removeFromSearch", "moveRecipeOutputs",
        "preserveOriginalTabs", "includeRecipeOutputs", "includeDatapackRecipes",
        "includeCustomRecipeStacks", "groupRecipeOutputsBySource", "deduplicateRecipeOutputs",
        "debugLogging", "syncFabricCreativeTabNavigation", "fabricLoaderWarning");
    // 'showEmptyTabs' is intentionally NOT a user-facing key: it has no
    // implementation on 26.3 (vanilla hard-hides empty CATEGORY tabs), so it
    // is neither advertised nor drawn, while old configs keep loading it.
    /** Free-text list keys (comma separated on input), with their purpose. */
    public static final List<String> LIST_KEYS = List.of(
        "excludeMods", "excludeItems", "technicalPatterns",
        "excludeGeneratedTabs", "forceGeneratedTabs");

    /**
     * Lowercase config key -> canonical camelCase key as used by every
     * {@link #applyKey} case, {@code BOOLEAN_KEYS} and {@code LIST_KEYS}.
     * Built once; anything in {@code applyKey} that is not in the two key
     * tables has to be added here as well (legacy aliases included).
     *
     * <p><b>Why this exists.</b> The command and config screen used to match
     * user-typed or lowercased keys against the camelCase case labels in two
     * independent switches; a key like {@code "singlegroupedtab"} matched
     * nothing, {@code valueOf} returned {@code null}, {@code
     * Boolean.parseBoolean(null)} is {@code false} - so every button except
     * {@code enabled} (the one all-lowercase key) read as permanently "OFF"
     * and every click re-applied {@code true}. Canonicalising is the fix.
     */
    private static final java.util.Map<String, String> KEY_CANONICAL;
    static {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        for (String key : BOOLEAN_KEYS) {
            map.put(key.toLowerCase(java.util.Locale.ROOT), key);
        }
        for (String key : LIST_KEYS) {
            map.put(key.toLowerCase(java.util.Locale.ROOT), key);
        }
        map.put("inclusionmode", "inclusionMode");
        map.put("includehiddenitems", "includeHiddenItems");
        KEY_CANONICAL = java.util.Collections.unmodifiableMap(map);
    }

    /**
     * Canonical camelCase spelling for any casing of a known config key, or
     * {@code null} when the key is unknown. Used by {@link #applyKey} and
     * {@link ModCommands#valueOf} so chat input and screen constants always
     * hit the same handlers.
     */
    public static @org.jspecify.annotations.Nullable String canonicalKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return KEY_CANONICAL.get(key.toLowerCase(java.util.Locale.ROOT));
    }

    /** Restores every field to the 0.3.1 default values shown in the javadocs above and below. */
    public void resetToDefaults() {
        TabConfig defaults = new TabConfig();
        enabled = defaults.enabled;
        includeHiddenItems = defaults.includeHiddenItems;
        excludeMods = new ArrayList<>(defaults.excludeMods);
        excludeItems = new ArrayList<>(defaults.excludeItems);
        technicalPatterns = new ArrayList<>(defaults.technicalPatterns);
        excludeGeneratedTabs = new ArrayList<>(defaults.excludeGeneratedTabs);
        forceGeneratedTabs = new ArrayList<>(defaults.forceGeneratedTabs);
        includeTechnicalItems = defaults.includeTechnicalItems;
        sortAlphabetically = defaults.sortAlphabetically;
        singleGroupedTab = defaults.singleGroupedTab;
        scanRecipes = defaults.scanRecipes;
        hideUncraftableItems = defaults.hideUncraftableItems;
        logDiscovery = defaults.logDiscovery;
        inclusionMode = defaults.inclusionMode;
        sortTabsAlphabetically = defaults.sortTabsAlphabetically;
        showEmptyTabs = defaults.showEmptyTabs;
        removeFromOriginalTabs = defaults.removeFromOriginalTabs;
        removeFromSearch = defaults.removeFromSearch;
        moveRecipeOutputs = defaults.moveRecipeOutputs;
        preserveOriginalTabs = defaults.preserveOriginalTabs;
        includeRecipeOutputs = defaults.includeRecipeOutputs;
        includeDatapackRecipes = defaults.includeDatapackRecipes;
        includeCustomRecipeStacks = defaults.includeCustomRecipeStacks;
        groupRecipeOutputsBySource = defaults.groupRecipeOutputsBySource;
        deduplicateRecipeOutputs = defaults.deduplicateRecipeOutputs;
        debugLogging = defaults.debugLogging;
        syncFabricCreativeTabNavigation = defaults.syncFabricCreativeTabNavigation;
        fabricLoaderWarning = defaults.fabricLoaderWarning;
    }

    /**
     * Applies one config key from a raw string (chat command or config screen).
     * Booleans accept {@code true}/{@code false}; lists take comma-separated
     * entries; {@code inclusionMode} takes the enum name.
     *
     * @return an error message, or {@code null} on success. Callers still need
     *     to {@link #save()} and trigger a {@link TabRebuild} themselves.
     */
    public @org.jspecify.annotations.Nullable String applyKey(String key, String raw) {
        if (key == null || key.isBlank()) {
            return "missing key";
        }
        // Any casing of a known key maps to its canonical camelCase spelling,
        // so the case labels below always match (screens pass constants, chat
        // users pass whatever they type).
        String canonical = canonicalKey(key);
        if (canonical == null) {
            return "unknown config key '" + key + "'";
        }
        String value = raw == null ? "" : raw.trim();
        try {
            switch (canonical) {
                case "inclusionMode" -> {
                    InclusionMode mode = InclusionMode.parse(value);
                    if (value.isEmpty()) {
                        return "usage: inclusionMode = " + String.join(" | ",
                            java.util.Arrays.stream(InclusionMode.values()).map(Enum::name).toArray(String[]::new));
                    }
                    setMode(mode);
                    return null;
                }
                // Legacy alias kept working: setting it maps the mode.
                case "includeHiddenItems" -> {
                    Boolean b = parseBool(value);
                    if (b == null) {
                        return "usage: includeHiddenItems = true|false";
                    }
                    setMode(b ? InclusionMode.ALL : InclusionMode.MISSING_ONLY);
                    return null;
                }
                case "enabled" -> enabled = value("enabled", value);
                // Booleans.
                case "includeTechnicalItems" -> includeTechnicalItems = value(key, value);
                case "sortAlphabetically" -> sortAlphabetically = value(key, value);
                case "singleGroupedTab" -> singleGroupedTab = value(key, value);
                case "scanRecipes" -> scanRecipes = value(key, value);
                case "hideUncraftableItems" -> hideUncraftableItems = value(key, value);
                case "logDiscovery" -> logDiscovery = value(key, value);
                case "showEmptyTabs" -> showEmptyTabs = value(key, value);
                case "sortTabsAlphabetically" -> sortTabsAlphabetically = value(key, value);
                case "removeFromOriginalTabs" -> removeFromOriginalTabs = value(key, value);
                case "removeFromSearch" -> removeFromSearch = value(key, value);
                case "moveRecipeOutputs" -> moveRecipeOutputs = value(key, value);
                case "preserveOriginalTabs" -> preserveOriginalTabs = value(key, value);
                case "includeRecipeOutputs" -> includeRecipeOutputs = value(key, value);
                case "includeDatapackRecipes" -> includeDatapackRecipes = value(key, value);
                case "includeCustomRecipeStacks" -> includeCustomRecipeStacks = value(key, value);
                case "groupRecipeOutputsBySource" -> groupRecipeOutputsBySource = value(key, value);
                case "deduplicateRecipeOutputs" -> deduplicateRecipeOutputs = value(key, value);
                case "debugLogging" -> debugLogging = value(key, value);
                case "syncFabricCreativeTabNavigation" -> syncFabricCreativeTabNavigation = value(key, value);
                case "fabricLoaderWarning" -> fabricLoaderWarning = value(key, value);
                // Lists, comma separated.
                case "excludeMods" -> excludeMods = toList(value);
                case "excludeItems" -> excludeItems = toList(value);
                case "technicalPatterns" -> technicalPatterns = toList(value);
                case "excludeGeneratedTabs" -> excludeGeneratedTabs = toList(value);
                case "forceGeneratedTabs" -> forceGeneratedTabs = toList(value);
                default -> {
                    return "unknown config key '" + key + "'";
                }
            }
            return null;
        } catch (Exception e) {
            return "failed to apply " + key + ": " + e.getMessage();
        }
    }

    // The two helpers above are tiny; failure is signalled by returning null
    // from value() so applyKey can return a uniform usage message.
    private static @org.jspecify.annotations.Nullable Boolean parseBool(String value) {
        if (value.equalsIgnoreCase("true") || value.equals("1") || value.equalsIgnoreCase("on") || value.equalsIgnoreCase("yes")) {
            return true;
        }
        if (value.equalsIgnoreCase("false") || value.equals("0") || value.equalsIgnoreCase("off") || value.equalsIgnoreCase("no")) {
            return false;
        }
        return null;
    }

    private static boolean value(String key, String value) {
        Boolean b = parseBool(value);
        if (b == null) {
            throw new IllegalArgumentException("usage: " + key + " = true|false");
        }
        return b;
    }

    private static List<String> toList(String value) {
        List<String> out = new ArrayList<>();
        for (String entry : value.split(",")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Loading / saving
    // ------------------------------------------------------------------

    public void load() {
        Path path = file();
        if (!Files.exists(path)) {
            save();
            AutoModTabs.LOGGER.info("[AutoModTabs] wrote default config to {}", path);
            return;
        }
        JsonObject json;
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            json = JsonParser.parseString(text).getAsJsonObject();
        } catch (Exception e) {
            AutoModTabs.LOGGER.error("[AutoModTabs] config {} is not readable JSON, using defaults", path, e);
            return;
        }
        try {
            apply(json);
            AutoModTabs.LOGGER.info("[AutoModTabs] loaded config from {}", path);
        } catch (Exception e) {
            AutoModTabs.LOGGER.error("[AutoModTabs] failed to apply config {} ({}), unchanged settings keep defaults", path, inclusionMode, e);
        }
        // Persist newly introduced defaults without disturbing existing values.
        save();
    }

    /** Applies every known key individually; a broken value changes nothing. Unknown keys are ignored. */
    private void apply(JsonObject json) {
        boolean hasLegacyMode = json.has("includeHiddenItems");
        boolean hasNewMode = json.has("inclusionMode");

        this.enabled = getBool(json, "enabled", this.enabled);
        this.excludeMods = getStringList(json, "excludeMods", this.excludeMods);
        this.excludeItems = getStringList(json, "excludeItems", this.excludeItems);
        this.technicalPatterns = getStringList(json, "technicalPatterns", this.technicalPatterns);
        this.excludeGeneratedTabs = getStringList(json, "excludeGeneratedTabs", this.excludeGeneratedTabs);
        this.forceGeneratedTabs = getStringList(json, "forceGeneratedTabs", this.forceGeneratedTabs);
        this.includeTechnicalItems = getBool(json, "includeTechnicalItems", this.includeTechnicalItems);
        this.sortAlphabetically = getBool(json, "sortAlphabetically", this.sortAlphabetically);
        this.singleGroupedTab = getBool(json, "singleGroupedTab", this.singleGroupedTab);
        this.scanRecipes = getBool(json, "scanRecipes", this.scanRecipes);
        this.hideUncraftableItems = getBool(json, "hideUncraftableItems", this.hideUncraftableItems);
        this.logDiscovery = getBool(json, "logDiscovery", this.logDiscovery);

        this.sortTabsAlphabetically = getBool(json, "sortTabsAlphabetically", this.sortTabsAlphabetically);
        this.showEmptyTabs = getBool(json, "showEmptyTabs", this.showEmptyTabs);
        this.removeFromOriginalTabs = getBool(json, "removeFromOriginalTabs", this.removeFromOriginalTabs);
        this.removeFromSearch = getBool(json, "removeFromSearch", this.removeFromSearch);
        this.moveRecipeOutputs = getBool(json, "moveRecipeOutputs", this.moveRecipeOutputs);
        this.preserveOriginalTabs = getBool(json, "preserveOriginalTabs", this.preserveOriginalTabs);
        this.includeRecipeOutputs = getBool(json, "includeRecipeOutputs", this.includeRecipeOutputs);
        this.includeDatapackRecipes = getBool(json, "includeDatapackRecipes", this.includeDatapackRecipes);
        this.includeCustomRecipeStacks = getBool(json, "includeCustomRecipeStacks", this.includeCustomRecipeStacks);
        this.groupRecipeOutputsBySource = getBool(json, "groupRecipeOutputsBySource", this.groupRecipeOutputsBySource);
        this.deduplicateRecipeOutputs = getBool(json, "deduplicateRecipeOutputs", this.deduplicateRecipeOutputs);
        this.debugLogging = getBool(json, "debugLogging", this.debugLogging);
        this.syncFabricCreativeTabNavigation = getBool(json, "syncFabricCreativeTabNavigation",
            this.syncFabricCreativeTabNavigation);
        this.fabricLoaderWarning = getBool(json, "fabricLoaderWarning", this.fabricLoaderWarning);

        // Inclusion mode: new key wins; otherwise map the legacy boolean.
        if (hasNewMode) {
            JsonElement raw = json.get("inclusionMode");
            String value = raw.isJsonPrimitive() ? raw.getAsString() : null;
            this.inclusionMode = InclusionMode.parse(value).name();
        } else if (hasLegacyMode) {
            InclusionMode mapped = getBool(json, "includeHiddenItems", true) ? InclusionMode.ALL : InclusionMode.MISSING_ONLY;
            this.inclusionMode = mapped.name();
            AutoModTabs.LOGGER.info("[AutoModTabs] mapped legacy includeHiddenItems={} to inclusionMode={}",
                getBool(json, "includeHiddenItems", true), mapped);
        }
        this.includeHiddenItems = mode() == InclusionMode.ALL;
    }

    private static boolean getBool(JsonObject json, String key, boolean fallback) {
        try {
            JsonElement raw = json.get(key);
            if (raw != null && raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isBoolean()) {
                return raw.getAsBoolean();
            }
            if (raw != null) {
                AutoModTabs.LOGGER.warn("[AutoModTabs] config key '{}' is not a boolean, keeping {}", key, fallback);
            }
        } catch (JsonParseException e) {
            AutoModTabs.LOGGER.warn("[AutoModTabs] config key '{}' invalid ({}), keeping {}", key, e, fallback);
        }
        return fallback;
    }

    private static List<String> getStringList(JsonObject json, String key, List<String> fallback) {
        try {
            JsonElement raw = json.get(key);
            if (raw != null && raw.isJsonArray()) {
                JsonArray array = raw.getAsJsonArray();
                List<String> out = new ArrayList<>(array.size());
                for (JsonElement e : array) {
                    if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                        out.add(e.getAsString());
                    } else {
                        AutoModTabs.LOGGER.warn("[AutoModTabs] config list '{}' has a non-string entry, skipping it", key);
                    }
                }
                return out;
            }
            if (raw != null) {
                AutoModTabs.LOGGER.warn("[AutoModTabs] config key '{}' is not a list, keeping {} entries", key, fallback.size());
            }
        } catch (JsonParseException e) {
            AutoModTabs.LOGGER.warn("[AutoModTabs] config list '{}' invalid ({}), keeping defaults", key, e);
        }
        return new ArrayList<>(fallback);
    }

    public void save() {
        // keepIncludeHiddenItems mirrors the mode for 0.1.0-compatible readers.
        this.includeHiddenItems = mode() == InclusionMode.ALL;
        Path path = file();
        try {
            JsonObject json = new JsonObject();
            json.addProperty("enabled", this.enabled);
            json.addProperty("includeHiddenItems", this.includeHiddenItems);
            json.add("excludeMods", toStringArray(this.excludeMods));
            json.add("excludeItems", toStringArray(this.excludeItems));
            json.add("technicalPatterns", toStringArray(this.technicalPatterns));
            json.add("excludeGeneratedTabs", toStringArray(this.excludeGeneratedTabs));
            json.add("forceGeneratedTabs", toStringArray(this.forceGeneratedTabs));
            json.addProperty("includeTechnicalItems", this.includeTechnicalItems);
            json.addProperty("sortAlphabetically", this.sortAlphabetically);
            json.addProperty("singleGroupedTab", this.singleGroupedTab);
            json.addProperty("scanRecipes", this.scanRecipes);
            json.addProperty("hideUncraftableItems", this.hideUncraftableItems);
            json.addProperty("logDiscovery", this.logDiscovery);
            json.addProperty("inclusionMode", this.inclusionMode);
            json.addProperty("sortTabsAlphabetically", this.sortTabsAlphabetically);
            json.addProperty("showEmptyTabs", this.showEmptyTabs);
            json.addProperty("removeFromOriginalTabs", this.removeFromOriginalTabs);
            json.addProperty("removeFromSearch", this.removeFromSearch);
            json.addProperty("moveRecipeOutputs", this.moveRecipeOutputs);
            json.addProperty("preserveOriginalTabs", this.preserveOriginalTabs);
            json.addProperty("includeRecipeOutputs", this.includeRecipeOutputs);
            json.addProperty("includeDatapackRecipes", this.includeDatapackRecipes);
            json.addProperty("includeCustomRecipeStacks", this.includeCustomRecipeStacks);
            json.addProperty("groupRecipeOutputsBySource", this.groupRecipeOutputsBySource);
            json.addProperty("deduplicateRecipeOutputs", this.deduplicateRecipeOutputs);
            json.addProperty("debugLogging", this.debugLogging);
            json.addProperty("syncFabricCreativeTabNavigation", this.syncFabricCreativeTabNavigation);
            json.addProperty("fabricLoaderWarning", this.fabricLoaderWarning);
            Files.writeString(path, GSON.toJson(json), StandardCharsets.UTF_8);
        } catch (IOException e) {
            AutoModTabs.LOGGER.error("[AutoModTabs] failed to save config to {}", path, e);
        }
    }

    private static JsonArray toStringArray(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }
}
