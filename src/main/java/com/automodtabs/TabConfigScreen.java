package com.automodtabs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * Paged in-game config screen for AutoModTabs, reachable through NeoForge's
 * mod-list "Config" button (registered as an {@code IConfigScreenFactory}
 * extension point in {@link AutoModTabs}).
 *
 * <p><b>Behaviour.</b> Every control writes through
 * {@link TabConfig#applyKey}, saves the config file immediately and nudges
 * {@link TabRebuild#requestRebuild()}, so tab contents follow the change
 * live, without restarting the game or the screen. Button handlers never
 * rebuild the widget tree during the click dispatch: toggles update their own
 * label in place, page switches and screen-wide refreshes are deferred via
 * {@code minecraft.execute(...)} so vanilla finishes routing the click first,
 * and every handler catches {@link Throwable} and logs it (0.3.1+).
 *
 * <p><b>Layout (0.4.0 rework).</b> One option per full-width row instead of
 * the old cramped two-column grid: a wide button showing a human-readable
 * name and its <i>real</i> current value (read through the case-insensitive
 * {@link ModCommands#valueOf}, the same one {@code /automodtabs get} uses),
 * its description on the tooltip, a gray page subtitle under the header, a
 * dedicated Compatibility page that shows the detected Fabric environment
 * next to the navigation-sync toggle, and diagnostics stacked with room for
 * their tooltips.
 */
public class TabConfigScreen extends Screen {
    /** One boolean row: config key, human-readable name, description. */
    private record Toggle(String key, String name, String description) {
    }

    /** Pages: {title, subtitle}. */
    private static final String[][] PAGES = {
        {"General", "Core behaviour"},
        {"Recipes", "Recipe scanning and recipe tabs"},
        {"Organization", "How entries move between tabs"},
        {"Mods", "Detected mods, per-mod tabs, manual inclusion"},
        {"Filters", "Mod, item and technical-text filters"},
        {"Compatibility", "Fabric API / Fabricated-NeoForge"},
        {"Diagnostics", "Reports, rebuild, reset"},
    };

    private static final List<Toggle> TOGGLES = List.of(
        // Page General (indices 0-5)
        new Toggle("enabled", "AutoModTabs",
            "Turn the whole mod off or on; generated tabs appear/disappear live."),
        new Toggle("singleGroupedTab", "Single grouped tab",
            "ON: one tab holds every modded item. OFF: one tab per mod."),
        new Toggle("sortTabsAlphabetically", "Sort tab bar A..Z",
            "Sort the generated tabs alphabetically."),
        new Toggle("sortAlphabetically", "Sort items A..Z",
            "Sort items inside the generated tabs alphabetically."),
        new Toggle("logDiscovery", "Discovery report in log",
            "Log the item/recipe classification report on startup and world join."),
        new Toggle("debugLogging", "Debug logging",
            "Verbose per-item debug logging."),
        new Toggle("fabricLoaderWarning", "Fabric detection notice",
            "Show a notice when Fabric API / a Fabric bridge is detected: the loader setup may affect how this mod works (no in-mod fix exists; use the Mods page 'force generated tab' for late-registering namespaces, restart needed). Individual-tab support for Fabric loaders is NOT provided from 0.5.1 on."),
        // Page Recipes (indices 7-14)
        new Toggle("scanRecipes", "Scan recipes",
            "Scan the active recipe manager for recipe outputs."),
        new Toggle("includeRecipeOutputs", "Show recipe outputs",
            "Also list recipe outputs in the generated recipe tabs."),
        new Toggle("groupRecipeOutputsBySource", "Recipe outputs per source",
            "ON: one recipe tab per mod. OFF: one flat Recipe outputs tab."),
        new Toggle("includeDatapackRecipes", "Include datapack recipes",
            "Also index recipes coming from datapacks instead of a loaded mod."),
        new Toggle("includeCustomRecipeStacks", "Keep customized stacks separate",
            "Recipe outputs with data components stay distinct entries."),
        new Toggle("deduplicateRecipeOutputs", "Deduplicate recipe outputs",
            "Do not list the same output stack twice."),
        new Toggle("hideUncraftableItems", "Hide uncraftable items",
            "Only show items a known recipe produces. Careful: many good items are not recipes; use the reset button if tabs end up near-empty."),
        // Page Organization (indices 13-17)
        new Toggle("includeTechnicalItems", "Show technical items",
            "Filter order: 1) excluded mods, 2) excluded items, 3) technical patterns. Technical items of a mod you excluded stay hidden even with this on (to see vanilla's, remove minecraft from excluded mods). With this off, matching items are filtered."),
        new Toggle("removeFromOriginalTabs", "Remove from original tabs",
            "Removes plain entries from other tabs once a generated tab shows them. A tab that would end up empty is kept intact entirely; nothing is removed without a live generated destination."),
        new Toggle("removeFromSearch", "Also remove from search",
            "Independent of the toggle above: matching entries leave the SEARCH result lists on the next rebuild; parent tabs are never touched by this setting."),
        new Toggle("moveRecipeOutputs", "Move customized recipe stacks",
            "OPT-IN (default off since 0.3.1): move customized recipe output stacks to their recipe tab; only exact component matches, and only when a generated recipe tab really shows the stack."),
        new Toggle("preserveOriginalTabs", "Never touch other tabs",
            "Invariant: this mod never deletes, hides or reorders original tabs."),
        // Page Compatibility (index 18)
        new Toggle("syncFabricCreativeTabNavigation", "Sync creative navigation (Fabric)",
            "EXPERIMENTAL, needs Fabric API: with Fabricated-NeoForge there are two navigation controls on the creative screen; this keeps both bars on the same page. Applies when the creative screen is next opened."));

    // Page -> [from, to) ranges into TOGGLES (0-6 General, 7-14 Recipes,
    // 15-19 Organization; Mods/Filters/Diagnostics are custom pages,
    // 20 Compatibility).
    private static final int[][] TOGGLE_PAGES = {
        {0, 7},
        {7, 14},
        {14, 19},
        {-1, -1},
        {-1, -1},
        {19, 20},
        {-1, -1},
    };

    private static final String[][] LIST_FIELDS = {
        {"excludeMods", "Mod namespaces that never get a generated tab.", "Excluded mods"},
        {"excludeItems", "Item ids: exact \"mod:item\", a whole \"mod:*\", or globs with *.", "Excluded items"},
        {"technicalPatterns", "Item ids or path substrings that count as technical.", "Technical patterns"},
    };

    private final Screen parent;
    private int page = 0;
    private @Nullable StringWidget feedbackWidget;
    private @Nullable String feedbackLine;

    public TabConfigScreen(Screen parent) {
        super(Component.literal("AutoModTabs configuration"));
        this.parent = parent;
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        this.feedbackWidget = null;

        int margin = 12;
        int usable = this.width - margin * 2;
        int row = 46;

        // Header: title + gray page subtitle.
        this.addRenderableWidget(new StringWidget(margin, 6, usable, 9,
            Component.literal("AutoModTabs - every change saves and applies live"), this.font));
        this.addRenderableWidget(new StringWidget(margin, 17, usable, 9,
            Component.literal(this.subtitle()).withStyle(ChatFormatting.GRAY), this.font));

        // Page bar.
        this.addPageBar(margin, 28, usable);

        // Boolean rows.
        int[] range = TOGGLE_PAGES[this.page];
        if (range[0] >= 0) {
            for (int i = range[0]; i < range[1]; i++) {
                this.addToggleRow(TOGGLES.get(i), margin, usable, row);
                row += 24;
            }
        }

        switch (this.page) {
            case 2 -> this.addModeRow(margin, usable, row);
            case 3 -> this.addModsPage(margin, usable);
            case 4 -> this.addListFields(margin, usable);
            case 5 -> this.addCompatibilityNotes(margin, usable, row);
            case 6 -> this.addDiagnostics(margin, usable);
            default -> {
            }
        }

        // Footer: feedback line + Done.
        this.feedbackWidget = new StringWidget(margin, this.height - 40, usable, 9,
            Component.literal(this.feedbackText()).withStyle(ChatFormatting.AQUA), this.font);
        this.addRenderableWidget(this.feedbackWidget);
        this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
            .bounds((this.width - 150) / 2, this.height - 26, 150, 20)
            .build());
    }

    private String subtitle() {
        return PAGES[this.page][0] + " - " + PAGES[this.page][1];
    }

    /** Row of page-switch buttons; the current page's button is disabled. */
    private void addPageBar(int margin, int y, int usable) {
        int count = PAGES.length;
        int each = Math.max(46, usable / count - 2);
        int total = each * count + (count - 1) * 2;
        int x = margin + Math.max(0, (usable - total) / 2);
        for (int i = 0; i < count; i++) {
            final int pageIndex = i;
            Button pageButton = Button.builder(Component.literal(PAGES[i][0]), b -> this.setPage(pageIndex))
                .bounds(x + i * (each + 2), y, each, 18)
                .tooltip(Tooltip.create(Component.literal(PAGES[i][1])))
                .build();
            pageButton.active = pageIndex != this.page;
            this.addRenderableWidget(pageButton);
        }
    }

    private void setPage(int pageIndex) {
        this.page = pageIndex;
        this.clearFocus();
        // Deferred: never rebuild the widget tree inside the click dispatch.
        if (this.minecraft != null) {
            this.minecraft.execute(this::rebuildWidgets);
        }
    }

    // ------------------------------------------------------------------
    // Boolean rows
    // ------------------------------------------------------------------

    /**
     * One toggle row: full-width button with "Human name: ON/OFF" plus
     * description tooltip; the click handler never rebuilds the widget tree.
     */
    private void addToggleRow(Toggle toggle, int margin, int usable, int y) {
        Button button = Button.builder(toggleLabel(toggle), b -> this.clickToggle(toggle, b))
            .bounds(margin, y, usable, 20)
            .tooltip(Tooltip.create(Component.literal(toggle.description())))
            .build();
        this.addRenderableWidget(button);
    }

    private static Component toggleLabel(Toggle toggle) {
        String value = ModCommands.valueOf(toggle.key());
        boolean on = value != null && Boolean.parseBoolean(value);
        ChatFormatting color = on ? ChatFormatting.GREEN : ChatFormatting.GRAY;
        return Component.literal(toggle.name() + ":  ")
            .append(Component.literal(on ? "ON" : "OFF").withStyle(color));
    }

    /** Press handler for one boolean toggle. */
    private void clickToggle(Toggle toggle, Button button) {
        try {
            String current = ModCommands.valueOf(toggle.key());
            boolean wasOn = current != null && Boolean.parseBoolean(current);
            String error = TabConfig.get().applyKey(toggle.key(), wasOn ? "false" : "true");
            if (error != null) {
                this.feedback(error);
                return;
            }
            TabConfig.get().save();
            TabRebuild.requestRebuild();
            button.setMessage(toggleLabel(toggle));
            this.feedback(toggle.name() + (wasOn ? " turned OFF" : " turned ON") + " - saved, applies live");
            if (TabConfig.get().debugLogging) {
                AutoModTabs.LOGGER.info("[AutoModTabs] config screen: set {} = {} ({})",
                    toggle.key(), !wasOn, toggle.description());
            }
        } catch (Throwable t) {
            AutoModTabs.LOGGER.error("[AutoModTabs] config screen: button '" + toggle.key() + "' failed", t);
            this.feedback("error: " + t);
        }
    }

    /** inclusionMode cycle row: ALL -> MISSING_ONLY -> HIDDEN_ONLY -> ALL. */
    private void addModeRow(int margin, int usable, int y) {
        Button button = Button.builder(modeCycleLabel(), b -> this.clickModeCycle(b))
            .bounds(margin, y, usable, 20)
            .tooltip(Tooltip.create(Component.literal(
                "ALL: every modded item. MISSING_ONLY: only items no other tab shows. HIDDEN_ONLY: only truly unreachable items.")))
            .build();
        this.addRenderableWidget(button);
    }

    private static Component modeCycleLabel() {
        return Component.literal("Inclusion mode:  ").withStyle(ChatFormatting.WHITE)
            .append(Component.literal(TabConfig.get().inclusionMode).withStyle(ChatFormatting.GREEN));
    }

    private void clickModeCycle(Button button) {
        try {
            String current = TabConfig.get().inclusionMode;
            String next = nextMode(current);
            String error = TabConfig.get().applyKey("inclusionMode", next);
            if (error != null) {
                this.feedback(error);
                return;
            }
            TabConfig.get().save();
            TabRebuild.requestRebuild();
            button.setMessage(modeCycleLabel());
            this.feedback("inclusionMode = " + next + " - saved, applies live");
        } catch (Throwable t) {
            AutoModTabs.LOGGER.error("[AutoModTabs] config screen: inclusionMode button failed", t);
            this.feedback("error: " + t);
        }
    }

    private static String nextMode(String current) {
        try {
            int index = InclusionMode.valueOf(current).ordinal();
            return InclusionMode.values()[(index + 1) % InclusionMode.values().length].name();
        } catch (IllegalArgumentException e) {
            return InclusionMode.ALL.name();
        }
    }

    // ------------------------------------------------------------------
    // Mods page: detection, per-mod generated-tab exclusion, forcing
    // ------------------------------------------------------------------

    /**
     * One detected mod as shown on the Mods page (0.5.0 feature). Detection
     * does NOT rely only on whether a mod registered a creative tab: the
     * contents of registered tabs are inspected, so a mod whose native tab
     * only holds part of its items is reported as partial, and a mod with
     * items but no native representation shows "no native tab".
     */
    private record DetectedMod(String namespace, String title, int itemsTotal,
                               int itemsShownElsewhere, String nativeTabDetail,
                               boolean hardExcluded, boolean perModExcluded, boolean forced) {
        Component line1() {
            String nativeShort = this.nativeTabDetail();
            if (nativeShort.length() > 40) {
                nativeShort = nativeShort.substring(0, 37) + "...";
            }
            return Component.literal(this.title + " (" + this.namespace + ") - " + nativeShort);
        }

        Component line2() {
            String counts = this.itemsShownElsewhere() < 0
                ? this.itemsTotal() + " item(s) (world needed for counts in existing tabs)"
                : this.itemsTotal() + " item(s), " + this.itemsShownElsewhere() + " in existing tabs";
            return Component.literal(counts).withStyle(ChatFormatting.GRAY);
        }
    }

    private Component perModButtonLabel(DetectedMod mod) {
        boolean generate = !mod.perModExcluded() && !mod.hardExcluded();
        return generate ? Component.literal("Generated tab: ON") : Component.literal("Generated tab: OFF");
    }

    private int modsPage;
    /** Session-only Mods page search text; empty shows everything. */
    private String modsSearch = "";
    /** True (default) hides mods with zero discoverable registered items. */
    private boolean modsHideNoItems = true;
    /** Set before a rebuild so the search box regains keyboard focus. */
    private boolean modsSearchFocusNext = false;

    /** Builds detection info for every loaded mod and item namespace present. */
    private List<DetectedMod> detectMods() {
        java.util.SortedSet<String> namespaces = new java.util.TreeSet<>();
        namespaces.addAll(Discovery.allNamespaces());
        if (net.neoforged.fml.ModList.get() != null) {
            for (net.neoforged.neoforgespi.language.IModInfo info : net.neoforged.fml.ModList.get().getMods()) {
                if (!AutoModTabs.MODID.equals(info.getModId())) {
                    namespaces.add(info.getModId());
                }
            }
        }
        List<DetectedMod> out = new ArrayList<>();
        if (namespaces.isEmpty()) {
            return out;
        }
        // "Items represented in existing tabs": the memoised shared-count set
        // over every built tab's contents (in-world only - at the title screen
        // components are not yet bound and counts would be meaningless).
        Set<Item> shownElsewhere = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.getConnection() != null && Discovery.componentsBound()) {
            CreativeModeTab.ItemDisplayParameters parameters = new CreativeModeTab.ItemDisplayParameters(
                mc.getConnection().enabledFeatures(),
                mc.player != null && mc.player.canUseGameMasterBlocks(),
                mc.level.registryAccess());
            shownElsewhere = Discovery.itemsShownElsewhere(parameters);
        }
        Map<String, List<String>> nativeTabs = new HashMap<>();
        for (CreativeModeTab tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
            Identifier tabId = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
            if (AutoModTabs.MODID.equals(tabId.getNamespace())) {
                continue;
            }
            nativeTabs.computeIfAbsent(tabId.getNamespace(), ns -> new ArrayList<>())
                .add(tab.getDisplayName().getString());
        }
        for (String ns : namespaces) {
            List<String> tabs = nativeTabs.getOrDefault(ns, new ArrayList<>());
            String detail = tabs.isEmpty() ? "no native tab" : String.join(", ", tabs);
            int itemsTotal = Discovery.itemsFor(ns).size();
            int shown = -1;
            if (shownElsewhere != null) {
                shown = 0;
                for (Item item : Discovery.itemsFor(ns)) {
                    if (shownElsewhere.contains(item)) {
                        shown++;
                    }
                }
            }
            out.add(new DetectedMod(ns, Discovery.displayNameFor(ns), itemsTotal, shown, detail,
                TabConfig.get().excludeMods.contains(ns),
                Discovery.perModTabExcluded(ns),
                TabConfig.get().forceGeneratedTabs.contains(ns)));
        }
        return out;
    }

    /** Mods page: force field, search/filter bar, then detection rows (15 per page). */
    private void addModsPage(int margin, int usable) {
        // (a) Manual inclusion ("force") field - apply-as-text like the other
        // filter fields, with per-entry validation. A forced tab can only be
        // created at registration time (the creative-tab registry freezes),
        // so changes land on the next game start - the feedback says so.
        this.addRenderableWidget(new StringWidget(margin, 40, usable, 9,
            Component.literal("Force a generated tab for a namespace (applies on next game start)")
                .withStyle(ChatFormatting.GRAY), this.font));
        TabConfig config = TabConfig.get();
        EditBox forceBox = new EditBox(this.font, margin, 50, usable - 76, 18, Component.literal(
            "Comma-separated mod ids that always get a dedicated generated tab"));
        forceBox.setMaxLength(4096);
        forceBox.setValue(String.join(", ", config.forceGeneratedTabs));
        this.addRenderableWidget(forceBox);
        this.addRenderableWidget(Button.builder(Component.literal("Apply"), b -> {
                String invalid = this.validateForceIds(forceBox.getValue());
                if (!invalid.isEmpty()) {
                    this.feedback("Rejected (no loaded mod / no items): " + invalid);
                    return;
                }
                String error = config.applyKey("forceGeneratedTabs", forceBox.getValue());
                if (error != null) {
                    this.feedback(error);
                    return;
                }
                config.save();
                this.feedback("forceGeneratedTabs saved - tab layout changes on the next game start");
            })
            .bounds(margin + usable - 72, 50, 72, 18)
            .tooltip(Tooltip.create(Component.literal(
                "Manual namespace inclusion: forces a dedicated generated tab for mods whose items register after the tab-registration point (e.g. Fabric mods through Fabricated-NeoForge). Restart required.")))
            .build());

        // (b) Live filter: search + the "mods with items only" toggle. Both
        // affect only this list view, never discovery itself.
        String query = this.modsSearch.trim().toLowerCase(java.util.Locale.ROOT);
        List<DetectedMod> filtered = this.detectMods().stream()
            .filter(m -> !this.modsHideNoItems || m.itemsTotal() > 0)
            .filter(m -> query.isEmpty()
                || m.title().toLowerCase(java.util.Locale.ROOT).contains(query)
                || m.namespace().toLowerCase(java.util.Locale.ROOT).contains(query))
            .toList();

        EditBox searchBox = new EditBox(this.font, margin, 74, usable - 320, 18, Component.literal(
            "Search by mod name or mod id"));
        searchBox.setMaxLength(256);
        searchBox.setValue(this.modsSearch);
        searchBox.setResponder(text -> {
            this.modsSearch = text;
            this.modsSearchFocusNext = true;
            this.modsPage = 0;
            if (this.minecraft != null) {
                this.minecraft.execute(this::rebuildWidgets);
            }
        });
        this.addRenderableWidget(searchBox);
        this.addRenderableWidget(Button.builder(Component.literal(this.modsHideNoItems
                    ? "With items only: ON" : "With items only: OFF"),
                b -> {
                    this.modsHideNoItems = !this.modsHideNoItems;
                    this.modsPage = 0;
                    if (this.minecraft != null) {
                        this.minecraft.execute(this::rebuildWidgets);
                    }
                })
            .bounds(margin + usable - 240, 74, 160, 18)
            .tooltip(Tooltip.create(Component.literal(
                "ON (default): hide mods with no discoverable registered items, so the mods holding items are easier to find. OFF: list every loaded mod. View-only: discovery is unaffected.")))
            .build());

        int perPage = 15;
        int pageCount = Math.max(1, (filtered.size() + perPage - 1) / perPage);
        this.modsPage = Math.max(0, Math.min(this.modsPage, pageCount - 1));

        Button prev = Button.builder(Component.literal("<"), b -> {
                this.modsPage--;
                if (this.minecraft != null) {
                    this.minecraft.execute(this::rebuildWidgets);
                }
            })
            .bounds(margin + usable - 72, 76, 20, 14)
            .build();
        Button next = Button.builder(Component.literal(">"), b -> {
                this.modsPage++;
                if (this.minecraft != null) {
                    this.minecraft.execute(this::rebuildWidgets);
                }
            })
            .bounds(margin + usable - 20, 76, 20, 14)
            .build();
        prev.active = this.modsPage > 0;
        next.active = this.modsPage < pageCount - 1;
        this.addRenderableWidget(prev);
        this.addRenderableWidget(next);
        this.addRenderableWidget(new StringWidget(margin + usable - 48, 79, 28, 9,
            Component.literal((this.modsPage + 1) + "/" + pageCount).withStyle(ChatFormatting.GRAY), this.font));

        if (this.modsSearchFocusNext) {
            this.modsSearchFocusNext = false;
            searchBox.setFocused(true);
            this.setFocused(searchBox);
        }

        // (c) Detection rows, fifteen per page.
        int from = this.modsPage * perPage;
        int to = Math.min(from + perPage, filtered.size());
        int y = 96;
        for (int i = from; i < to; i++) {
            DetectedMod mod = filtered.get(i);
            String toolText = mod.title() + " (mod id: " + mod.namespace() + ")\n"
                + mod.nativeTabDetail()
                + "\nItems: " + (mod.itemsShownElsewhere() < 0
                    ? mod.itemsTotal() + " registered (counts in existing tabs need a world)"
                    : mod.itemsShownElsewhere() + " of " + mod.itemsTotal()
                        + " shown in existing tabs"
                        + (mod.itemsShownElsewhere() < mod.itemsTotal() ? " - PARTIAL representation" : " (all items represented)"))
                + "\nexcludeMods: " + (mod.hardExcluded() ? "yes (ignored entirely)" : "no")
                + "\nforced generated tab: " + (mod.forced() ? "yes" : "no");
            this.addRenderableWidget(setTooltip(new StringWidget(margin, y, usable - 132, 9,
                mod.line1(), this.font), Tooltip.create(Component.literal(toolText))));
            this.addRenderableWidget(new StringWidget(margin, y + 11, usable - 132, 9,
                mod.line2(), this.font));
            if (mod.hardExcluded()) {
                Button inactive = Button.builder(Component.literal("excluded (excludeMods)"), b -> { })
                    .bounds(margin + usable - 128, y, 128, 20)
                    .tooltip(Tooltip.create(Component.literal(
                        "excludeMods contains this mod: AutoModTabs ignores it entirely. Remove the namespace from excluded mods on the Filters page first.")))
                    .build();
                inactive.active = false;
                this.addRenderableWidget(inactive);
            } else {
                this.addRenderableWidget(Button.builder(this.perModButtonLabel(mod), b -> this.clickPerModToggle(mod, b))
                    .bounds(margin + usable - 128, y, 128, 20)
                    .tooltip(Tooltip.create(Component.literal(
                        "OFF: no generated tab for this mod - its native tabs stay authoritative and the grouped tab skips it. ON: AutoModTabs generates a tab. Applies live.")))
                    .build());
            }
            y += 24;
        }
    }

    /** Text widgets only need the tooltip call routed through {@code setTooltip}. */
    private static <W extends net.minecraft.client.gui.components.AbstractWidget> W setTooltip(W widget, Tooltip tooltip) {
        widget.setTooltip(tooltip);
        return widget;
    }

    /** Sets or clears one namespace's per-mod generated-tab exclusion. */
    private void clickPerModToggle(DetectedMod mod, Button button) {
        TabConfig config = TabConfig.get();
        try {
            java.util.List<String> list = new ArrayList<>(config.excludeGeneratedTabs);
            boolean nowExcluded = list.contains(mod.namespace());
            if (nowExcluded) {
                list.remove(mod.namespace());
            } else {
                list.add(mod.namespace());
            }
            String error = config.applyKey("excludeGeneratedTabs", String.join(",", list));
            if (error != null) {
                this.feedback(error);
                return;
            }
            config.save();
            TabRebuild.requestRebuild();
            DetectedMod updated = this.detectMods().stream()
                .filter(m -> m.namespace().equals(mod.namespace()))
                .findFirst().orElse(mod);
            button.setMessage(this.perModButtonLabel(updated));
            this.feedback(mod.namespace() + (nowExcluded
                ? " receives a generated tab again (live)"
                : " no longer receives a generated tab (live); its original tabs keep everything"));
        } catch (Throwable t) {
            AutoModTabs.LOGGER.error("[AutoModTabs] config screen: per-mod toggle for '" + mod.namespace() + "' failed", t);
            this.feedback("error: " + t);
        }
    }

    /** Comma-separated entry validation for the forced-namespace field. */
    private String validateForceIds(String rawValue) {
        List<String> invalid = new ArrayList<>();
        for (String entry : rawValue.split(",")) {
            String ns = entry.trim();
            if (ns.isEmpty()) {
                continue;
            }
            if (ns.equals(AutoModTabs.MODID)) {
                invalid.add(ns + " (own namespace)");
                continue;
            }
            boolean isModId = net.neoforged.fml.ModList.get() != null
                && net.neoforged.fml.ModList.get().getModContainerById(ns).isPresent();
            if (!isModId && Discovery.itemsFor(ns).isEmpty()) {
                invalid.add(ns);
            }
        }
        return String.join(", ", invalid);
    }

    // ------------------------------------------------------------------
    // Free-text list fields
    // ------------------------------------------------------------------

    private void addListFields(int margin, int usable) {
        TabConfig config = TabConfig.get();
        this.addRenderableWidget(new StringWidget(margin, 54, usable, 9,
            Component.literal("Comma-separated lists; Apply each line, tab contents rebuild live.")
                .withStyle(ChatFormatting.GRAY), this.font));
        int y = 68;
        for (String[] field : LIST_FIELDS) {
            String key = field[0];
            this.addRenderableWidget(new StringWidget(margin, y, usable, 9,
                Component.literal(field[2]), this.font));
            EditBox box = new EditBox(this.font, margin, y + 11, usable - 74, 18, Component.literal(field[1]));
            box.setMaxLength(4096);
            box.setValue(joined(key));
            this.addRenderableWidget(box);
            this.addRenderableWidget(Button.builder(Component.literal("Apply"), b -> {
                    try {
                        String error = config.applyKey(key, box.getValue());
                        if (error != null) {
                            this.feedback(error);
                            return;
                        }
                        config.save();
                        TabRebuild.requestRebuild();
                        this.feedback(key + " updated - saved, applies live");
                    } catch (Throwable t) {
                        AutoModTabs.LOGGER.error("[AutoModTabs] config screen: '" + key + "' apply failed", t);
                        this.feedback("error: " + t);
                    }
                })
                .bounds(margin + usable - 70, y + 11, 70, 18)
                .tooltip(Tooltip.create(Component.literal(field[1])))
                .build());
            y += 42;
        }
    }

    private static String joined(String key) {
        return switch (key) {
            case "excludeMods" -> String.join(", ", TabConfig.get().excludeMods);
            case "excludeItems" -> String.join(", ", TabConfig.get().excludeItems);
            case "technicalPatterns" -> String.join(", ", TabConfig.get().technicalPatterns);
            default -> "";
        };
    }

    // ------------------------------------------------------------------
    // Compatibility page
    // ------------------------------------------------------------------

    private void addCompatibilityNotes(int margin, int usable, int y) {
        this.addRenderableWidget(new StringWidget(margin, y + 6, usable, 9,
            Component.literal("Detected environment: " + FabricCompat.describe()), this.font));
        this.addRenderableWidget(new StringWidget(margin, y + 20, usable, 9,
            Component.literal("EXPERIMENTAL: with Fabric API loaded there are two independent navigation controls on the")
                .withStyle(ChatFormatting.GRAY), this.font));
        this.addRenderableWidget(new StringWidget(margin, y + 30, usable, 9,
            Component.literal("creative screen. This toggle keeps both bars roughly on the same page, but the two")
                .withStyle(ChatFormatting.GRAY), this.font));
        this.addRenderableWidget(new StringWidget(margin, y + 40, usable, 9,
            Component.literal("inventory implementations are not designed to run together - the last page may not")
                .withStyle(ChatFormatting.GRAY), this.font));
        this.addRenderableWidget(new StringWidget(margin, y + 50, usable, 9,
            Component.literal("always match, and tabs can look inaccessible. Reducing pages (MISSING_ONLY) avoids it.")
                .withStyle(ChatFormatting.GRAY), this.font));
        this.addRenderableWidget(new StringWidget(margin, y + 60, usable, 9,
            Component.literal("It does nothing without Fabric API, and the creative screen picks it up when next opened.")
                .withStyle(ChatFormatting.GRAY), this.font));
    }

    // ------------------------------------------------------------------
    // Diagnostics page
    // ------------------------------------------------------------------

    private void addDiagnostics(int margin, int usable) {
        int y = 52;
        this.addRenderableWidget(Button.builder(
                Component.literal("Reset all settings to defaults"),
                b -> {
                    try {
                        TabConfig.get().resetToDefaults();
                        TabConfig.get().save();
                        TabRebuild.requestRebuild();
                        this.feedback("Defaults restored, saved and applied live.");
                        if (this.minecraft != null) {
                            this.minecraft.execute(this::rebuildWidgets); // deferred refresh of all labels
                        }
                    } catch (Throwable t) {
                        AutoModTabs.LOGGER.error("[AutoModTabs] config screen: reset button failed", t);
                        this.feedback("Reset failed: " + t);
                    }
                })
            .bounds(margin, y, usable, 20)
            .tooltip(Tooltip.create(Component.literal(
                "Undoes a bad toggle session: restores the safe 0.4.0 defaults (inclusionMode=ALL, hideUncraftableItems=false, removals off).")))
            .build());
        this.addRenderableWidget(Button.builder(
                Component.literal("Write discovery report to the log"),
                b -> {
                    try {
                        DiscoveryReport.runReport();
                        this.feedback("Report written to the client log (latest.log).");
                    } catch (Throwable t) {
                        AutoModTabs.LOGGER.error("[AutoModTabs] config screen: manual report failed", t);
                        this.feedback("Report failed: " + t);
                    }
                })
            .bounds(margin, y + 28, usable, 20)
            .tooltip(Tooltip.create(Component.literal("Builds every tab and logs item/recipe classification.")))
            .build());
        this.addRenderableWidget(Button.builder(
                Component.literal("Force rebuild of tab contents"),
                b -> {
                    try {
                        TabRebuild.requestRebuild();
                        this.feedback("Rebuild requested - open the creative inventory to see the change.");
                    } catch (Throwable t) {
                        AutoModTabs.LOGGER.error("[AutoModTabs] config screen: manual rebuild failed", t);
                        this.feedback("Rebuild failed: " + t);
                    }
                })
            .bounds(margin, y + 56, usable, 20)
            .tooltip(Tooltip.create(Component.literal("Forces an immediate vanilla tab-contents rebuild with the current config.")))
            .build());
        Discovery.ensureCurrent();
        this.addRenderableWidget(new StringWidget(margin, y + 88, usable, 9,
            Component.literal("Namespaces: " + Discovery.visibleNamespaces().size()
                + " | recipe data " + (RecipeIndex.hasData() ? "available" : "not yet available (in-world only)")
                + " | " + FabricCompat.describe()).withStyle(ChatFormatting.GRAY),
            this.font));
    }

    // ------------------------------------------------------------------
    // Shared plumbing
    // ------------------------------------------------------------------

    private void feedback(String message) {
        this.feedbackLine = message;
        if (this.feedbackWidget != null) {
            this.feedbackWidget.setMessage(Component.literal(message).withStyle(ChatFormatting.AQUA));
        }
    }

    private String feedbackText() {
        return this.feedbackLine == null
            ? "Tip: /automodtabs help in chat lists every command with examples."
            : this.feedbackLine;
    }

    /** Keep the 0.3.x name alive as the single feedback entry point. */
    private void feedbackTracked(String message) {
        this.feedback(message);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(this.parent);
        }
    }
}
