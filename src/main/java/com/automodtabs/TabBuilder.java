package com.automodtabs;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.jspecify.annotations.Nullable;

/**
 * Registers the auto-discovered creative tabs.
 *
 * <p>Timing: the NeoForge {@code RegisterEvent} for the creative-mode-tab
 * registry fires <b>after</b> the item registry has been filled (see
 * {@code GameData#getRegistrationOrder()}), so the discovery step always sees
 * a complete item registry. Tab contents are built lazily by vanilla when tab
 * contents are requested, so contents re-derive from the current config on
 * every rebuild.
 *
 * <p><b>Structural tabs, live contents.</b> The creative-mode-tab registry is
 * builtin and frozen at startup, so tabs can never be added or removed later.
 * v0.3 therefore registers <i>every structural tab unconditionally</i> - the
 * grouped {@code all_mods} tab, one {@code mod_<namespace>} tab per item
 * namespace, the flat {@code mod_recipes} tab, one {@code recipes_<namespace>}
 * tab per known loaded-mod recipe source, and the {@code recipes_other}
 * fallback tab - and lets the populators in {@link Discovery} decide, from the
 * <i>current</i> config on every rebuild, which of them receive contents. A
 * tab whose contents end up empty is auto-hidden by vanilla
 * ({@code CreativeModeTab#shouldDisplay} for {@code CATEGORY} tabs), which is
 * what makes live config switching (including {@code singleGroupedTab},
 * {@code groupRecipeOutputsBySource} and {@code excludeMods}) work without a
 * game restart - see {@link TabRebuild}.
 *
 * <p>Known-at-registration limitation: namespaces (item or recipe) that only
 * exist after registration - datapack-only recipe sources and items registered
 * by later mods - have no dedicated tab. The group tab catches late item
 * namespaces as "latecomers" in per-mod mode; datapack recipe sources fall back
 * to the always-present {@code recipes_other} tab (e.g. Pocket Portals'
 * {@code dqc.portal} namespace, which ships no mod at all).
 *
 * <p>Tab kinds:
 * <ul>
 * <li><b>Registry tabs</b> {@code mod_<namespace>} - one per namespace with
 * registered items (contents gated per mode), plus the grouped
 * {@code all_mods} tab.</li>
 * <li><b>Recipe tabs</b> {@code recipes_<namespace>} / {@code recipes_other} /
 * flat {@code mod_recipes} - outputs grouped by their recipe-id namespace,
 * with the recipe-source mode decided live.</li>
 * </ul>
 *
 * <p>Component timing: nothing below reads item components during
 * registration. Discovery stores {@link Item} references; the icon suppliers
 * and the {@code displayItems} callbacks only run when tab contents are
 * actually requested (world data loaded, components bound) - see
 * {@code Discovery#componentsBound()}.
 *
 * <p>No tabs-before/after hints are set by default: NeoForge appends
 * hint-less mod tabs after the last vanilla category tab in a stable order,
 * which keeps vanilla tabs untouched. With {@code sortTabsAlphabetically},
 * ALL structural tabs are chained with {@code tabsBefore}/{@code tabsAfter}
 * registry-order hints (the supported ordering mechanism) in alphabetical
 * title order - <i>a registration-time snapshot</i>: the ordering edges are
 * baked into the immutable tab objects at startup, so changing the toggle
 * needs a game restart (contents still apply live, see {@link TabRebuild}).
 */
public final class TabBuilder {
    private TabBuilder() {
    }

    public static void onCreate(RegisterEvent event) {
        if (event.getRegistryKey() != Registries.CREATIVE_MODE_TAB) {
            return;
        }
        // Always discover - even when the config says disabled: the structural
        // tabs still register (with empty, auto-hidden contents) so that live
        // enabling works without a game restart.
        Discovery.discover();

        boolean sortTabs = TabConfig.get().sortTabsAlphabetically;

        /*
         * Structural tab layout. Every shape registers unconditionally; which
         * one receives contents is decided live per rebuild in Discovery.
         *
         * Ordering: NeoForge 26.3 does NOT keep creative-tab registration
         * order. CreativeModeTabRegistry.sortTabs() build a topological graph
         * in which every mod tab is only anchored "after the last vanilla
         * tab", with no relative edges - ties fall back to the alphabetical
         * registry-id string. To actually order the generated tabs, the tabs
         * carry tabsBefore/tabsAfter edges (the supported mechanism, read
         * again by every ordering pass).
         *
         * sortTabsAlphabetically=true chains ALL our structural tabs (grouped,
         * per-mod and recipe tabs) alphabetically by title:
         *     last-vanilla-category -> ours[0] -> ours[1] -> ...
         * The chain is a registration-time snapshot: the creative-mode-tab
         * registry is frozen at startup, so changing the toggle needs a game
         * restart to take effect (documented in the config screen).
         * sortTabsAlphabetically=false registers without ordering hints -
         * exactly the pre-0.4.1 behaviour (id-alphabetical tie-break).
         */
        List<TabSpec> specs = new ArrayList<>();
        specs.add(new TabSpec(
            Identifier.fromNamespaceAndPath(AutoModTabs.MODID, "all_mods"),
            "Automatic Modded Tabs",
            Discovery.iconProvider(null),
            null, null));

        List<String> namespaces = new ArrayList<>(Discovery.allNamespaces());
        namespaces.remove(AutoModTabs.MODID);
        // Forced namespaces (0.5.0, config loaded in the mod constructor before
        // this event) get a structural tab even when their items are not
        // registered yet: covers Fabric-style items that register after this
        // creative-tab RegisterEvent through Fabricated-NeoForge and would
        // otherwise only surface in the grouped tab. The live populators pick
        // the items up as soon as they exist (Discovery.ensureCurrent).
        for (String forced : TabConfig.get().forceGeneratedTabs) {
            if (forced == null || forced.isBlank()
                || namespaces.contains(forced) || forced.equals(AutoModTabs.MODID)) {
                continue; // already tabbed, duplicate tab, or our own namespace
            }
            boolean isModId = net.neoforged.fml.ModList.get() != null
                && net.neoforged.fml.ModList.get().getModContainerById(forced).isPresent();
            boolean hasItems = !Discovery.itemsFor(forced).isEmpty();
            if (isModId || hasItems) {
                namespaces.add(forced);
                if (TabConfig.get().logDiscovery) {
                    AutoModTabs.LOGGER.info("[AutoModTabs] forced generated tab for namespace {} ({} registered items at registration)",
                        forced, Discovery.itemsFor(forced).size());
                }
            } else {
                AutoModTabs.LOGGER.warn("[AutoModTabs] forceGeneratedTabs entry '{}' matches no loaded mod and no registered items - not registering a tab",
                    forced);
            }
        }
        if (sortTabs) {
            namespaces.sort(java.util.Comparator.comparing(Discovery::displayNameFor, String.CASE_INSENSITIVE_ORDER));
        }
        for (String namespace : namespaces) {
            specs.add(new TabSpec(
                Identifier.fromNamespaceAndPath(AutoModTabs.MODID, "mod_" + namespace),
                Discovery.displayNameFor(namespace),
                Discovery.iconProvider(namespace),
                namespace, null));
        }

        // Recipe tabs (independent discovery path, see RecipeIndex). All three
        // shapes register; the populators pick the live shape of the current
        // config ("flat" vs "by source") and leave the unused ones empty.
        specs.add(new TabSpec(
            Identifier.fromNamespaceAndPath(AutoModTabs.MODID, "mod_recipes"),
            "Recipe Outputs",
            Discovery.recipeIconProvider(Discovery.RECIPE_SOURCE_ALL),
            null, Discovery.RECIPE_SOURCE_ALL));
        for (String namespace : namespaces) {
            specs.add(new TabSpec(
                Identifier.fromNamespaceAndPath(AutoModTabs.MODID, "recipes_" + sanitize(namespace)),
                recipeTabTitle(namespace),
                Discovery.recipeIconProvider(namespace),
                null, namespace));
        }
        // Datapack-style sources are only known at world load, so all of them
        // land in this one always-present fallback tab.
        specs.add(new TabSpec(
            Identifier.fromNamespaceAndPath(AutoModTabs.MODID, "recipes_other"),
            "Datapack Recipe Outputs",
            Discovery.recipeIconProvider(Discovery.RECIPE_SOURCE_DATAPACKS),
            null, Discovery.RECIPE_SOURCE_DATAPACKS));

        if (sortTabs) {
            specs.sort(java.util.Comparator.comparing(spec -> spec.title().toLowerCase(java.util.Locale.ROOT)));
        }

        int registered = 0;
        Identifier anchor = sortTabs ? lastVanillaOrderAnchor() : null;
        for (int i = 0; i < specs.size(); i++) {
            TabSpec spec = specs.get(i);
            Identifier before = sortTabs && i == 0 ? anchor : null;
            Identifier after = sortTabs && i + 1 < specs.size() ? specs.get(i + 1).id() : null;
            registerTab(event, spec, before, after);
            if (spec.namespace() != null) {
                Discovery.markTabbed(spec.namespace());
            }
            registered++;
        }

        AutoModTabs.LOGGER.info("[AutoModTabs] registered {} automatic creative tab(s) "
            + "for {} namespace(s); contents follow the live config (tab ordering: {})",
            registered, namespaces.size(), sortTabs ? "alphabetical (registration-time)" : "default registry order");
    }

    /** One structural tab to register: id, title, icon and which populators serve it. */
    private record TabSpec(Identifier id, String title, java.util.function.Supplier<ItemStack> icon,
                           @org.jspecify.annotations.Nullable String namespace,
                           @org.jspecify.annotations.Nullable String recipeSource) {
    }

    /**
     * The registry id of the 10th non-default creative tab - NeoForge's
     * "last vanilla tab" anchor ({@code CreativeModeTabRegistry.sortTabs()}
     * uses the same definition: skip hotbar/search/OP/inventory, count 10).
     * Chained generated tabs need it as their {@code tabsBefore} anchor
     * because tabs with ordering hints lose the automatic
     * {@code lastVanilla -> tab} edge.
     */
    private static @org.jspecify.annotations.Nullable Identifier lastVanillaOrderAnchor() {
        try {
            List<ResourceKey<CreativeModeTab>> indexed = new ArrayList<>();
            for (CreativeModeTab tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
                ResourceKey<CreativeModeTab> key =
                    BuiltInRegistries.CREATIVE_MODE_TAB.getResourceKey(tab).orElse(null);
                if (key == null) {
                    continue;
                }
                if (key == CreativeModeTabs.HOTBAR || key == CreativeModeTabs.SEARCH
                    || key == CreativeModeTabs.OP_BLOCKS || key == CreativeModeTabs.INVENTORY) {
                    continue;
                }
                indexed.add(key);
                if (indexed.size() == 10) {
                    return key.identifier();
                }
            }
            return indexed.isEmpty() ? null : indexed.get(indexed.size() - 1).identifier();
        } catch (Throwable t) {
            AutoModTabs.LOGGER.warn("[AutoModTabs] could not determine the ordering anchor for alphabetical tabs: {}",
                t.toString());
            return null;
        }
    }

    private static String recipeTabTitle(String namespace) {
        return Discovery.displayNameFor(namespace) + " (recipes)";
    }

    /** Keeps only characters that are legal in an Identifier path. */
    private static String sanitize(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
        StringBuilder sb = new StringBuilder();
        for (char c : lower.toCharArray()) {
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-';
            sb.append(ok ? c : '_');
        }
        return sb.toString();
    }

    private static void registerTab(RegisterEvent event, TabSpec spec,
                                    @org.jspecify.annotations.Nullable Identifier tabsBefore,
                                    @org.jspecify.annotations.Nullable Identifier tabsAfter) {
        Identifier id = spec.id();
        String title = spec.title();
        java.util.function.Supplier<ItemStack> icon = spec.icon();
        String namespace = spec.namespace();
        String recipeSource = spec.recipeSource();
        CreativeModeTab.Builder builder = CreativeModeTab.builder()
            // Type defaults to CATEGORY, which vanilla auto-hides when the
            // tab has no contents (safe empty-groups behaviour).
            .title(Component.literal(title))
            .icon(icon)
            .displayItems((parameters, output) -> {
                if (namespace != null) {
                    Discovery.populate(parameters, output, namespace);
                } else if (recipeSource != null) {
                    Discovery.populateRecipe(parameters, output, recipeSource);
                } else {
                    // The grouped all_mods tab (bug 0.4.0: neither branch
                    // above fired, so it registered empty and vanilla
                    // auto-hid it - singleGroupedTab then appeared to
                    // delete every generated tab without a replacement).
                    Discovery.populate(parameters, output, null);
                }
            });
        // Only set hints that were actually requested: NeoForge gives every
        // tab WITH hints a different ordering treatment (no last-vanilla
        // anchor) than hint-less tabs, so the default mode must stay
        // hint-less to preserve the working pre-0.4.1 order.
        if (tabsBefore != null) {
            builder.withTabsBefore(tabsBefore);
        }
        if (tabsAfter != null) {
            builder.withTabsAfter(tabsAfter);
        }
        event.register(Registries.CREATIVE_MODE_TAB, id, builder::build);
        if (TabConfig.get().logDiscovery) {
            AutoModTabs.LOGGER.info("[AutoModTabs] registered tab {} [{}]", id, title);
        }
    }
}
