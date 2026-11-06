package com.automodtabs;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/**
 * Implements the "item moving" half of the tab organisation.
 *
 * <p>NeoForge 26.3 patches {@code CreativeModeTab#buildContents} to funnel
 * every tab's entries through a {@link BuildCreativeModeTabContentsEvent}
 * (posted to every mod bus via {@code ModLoader#postEvent}) with separate,
 * mutable parent-entry and search-entry sets. That is the supported API for
 * filtering <i>other</i> tabs' contents - we never touch registry entries,
 * never unregister a tab and never touch what a tab's own mod adds later:
 * the event is fired on every rebuild with the freshly generated entries, so
 * filtering is idempotent per build cycle.
 *
 * <p><b>Two independent surfaces (0.4.1 semantics).</b> Which entry sets are
 * filtered is decided by two independent toggles, nothing else:
 *
 * <ul>
 * <li><b>{@code removeFromOriginalTabs}</b> filters <i>parent</i> entries: a
 * plain (default-component) entry of an item that one of <i>our</i>
 * generated tabs displays, so the item appears in its generated tab instead
 * of being duplicated. Two safety rails apply:
 * <ul>
 * <li><b>Destination guarantee:</b> the predicate only matches items that one
 * of our generated tabs is demonstrably building right now
 * ({@link Discovery#ourGeneratedItems}) - if no live destination exists,
 * nothing is removed.</li>
 * <li><b>Never-empty guard:</b> if every entry of a tab would be removed, the
 * tab is kept intact instead - a foreign tab that only shows content our
 * generated tabs mirror keeps all of it. This is what keeps original modded
 * tab groups visible; a grouped tab is never emptied just because some of
 * its entries belong to a mod with a generated tab.</li>
 * </ul></li>
 * <li><b>{@code removeFromSearch}</b> independently filters the same
 * predicates on <i>search</i> entries - the per-tab
 * {@code getSearchTabDisplayItems()} lists that the vanilla search tab
 * unifies ({@code CreativeModeTabs.registerTabs} search-tab generator). It
 * does not require {@code removeFromOriginalTabs}, and never touches parent
 * entries: unsetting the search toggle puts items back into search on the
 * next rebuild. in non-{@code ALL} inclusion modes the plain predicate is
 * not a full mirror, so the plain-search strip stays off there; the
 * customized-variant strip follows {@code moveRecipeOutputs} in any mode.</li>
 * <li><b>{@code moveRecipeOutputs}</b>: an entry whose data components match
 * a customized {@link RecipeEntry} of an included recipe source - so a
 * customized variant moves to its source tab. Matching always compares the
 * complete component set, so the underlying vanilla item's plain variant is
 * never affected.</li>
 * </ul>
 *
 * <p>The listener never throws: any failure logs instead of breaking a tab.
 */
final class TabOrganizer {
    private TabOrganizer() {
    }

    public static void onBuildTabContents(BuildCreativeModeTabContentsEvent event) {
        try {
            handle(event);
        } catch (Throwable t) {
            if (TabConfig.get().debugLogging) {
                AutoModTabs.LOGGER.error("[AutoModTabs] tab filter failed for {}",
                    event.getTabKey().identifier(), t);
            } else {
                AutoModTabs.LOGGER.warn("[AutoModTabs] tab filter failed for {}",
                    event.getTabKey().identifier());
            }
        }
    }

    private static void handle(BuildCreativeModeTabContentsEvent event) {
        TabConfig config = TabConfig.get();
        if (!config.enabled) {
            return;
        }
        Identifier tabId = event.getTabKey().identifier();
        if (AutoModTabs.MODID.equals(tabId.getNamespace())) {
            return; // never filter our own generated tabs
        }
        if (!Discovery.componentsBound()) {
            // Entries of other tabs cannot even exist in stack form yet, and
            // our own content cannot be built; world data re-builds tabs and
            // fires this event again later.
            return;
        }
        // Parent-side moves: plain entries only in ALL mode (the only mode in
        // which our generated tabs are a full mirror of the item set, so
        // every removed entry is guaranteed a live generated destination).
        boolean movePlain = config.removeFromOriginalTabs && config.mode() == InclusionMode.ALL;
        // Variants move only when recipe scanning has data at all AND the
        // customized stacks are actually shown in a generated recipe tab -
        // that is the destination guarantee, checked per entry in
        // {@link #isCustomizedRecipeVariant}.
        boolean moveVariants = config.moveRecipeOutputs && config.includeRecipeOutputs
            && config.includeCustomRecipeStacks && config.scanRecipes && RecipeIndex.hasData();
        // Search-side strips: independent of the parent-side toggles. Plain
        // entries strip from our tabs' mirrored set - only meaningful in ALL
        // mode for the same destination-mirror reason; variants strip when
        // the variant machinery has destinations.
        boolean stripSearchPlain = config.removeFromSearch && config.mode() == InclusionMode.ALL;
        boolean stripSearchVariants = config.removeFromSearch && moveVariants;
        if (!movePlain && !moveVariants && !stripSearchPlain && !stripSearchVariants) {
            return;
        }
        if (!config.preserveOriginalTabs) {
            // Documented as an invariant; we still never destroy tabs, so this
            // only records that the user chose the non-promise setting.
            AutoModTabs.LOGGER.warn("[AutoModTabs] preserveOriginalTabs=false has no effect: this mod never deletes or disables tabs");
        }

        if (movePlain || moveVariants) {
            // Two-pass parent handling: classify first, then decide - this
            // is what implements the destination guarantee and the
            // never-empty guard without ever losable moves.
            List<ItemStack> parents = List.copyOf(event.getParentEntries());
            List<ItemStack> removable = new ArrayList<>();
            for (ItemStack entry : parents) {
                boolean plain = movePlain && isPlainMovedItem(event, entry);
                boolean variant = moveVariants && isCustomizedRecipeVariant(entry);
                if (plain || variant) {
                    removable.add(entry);
                }
            }
            if (!removable.isEmpty()) {
                if (removable.size() >= parents.size()) {
                    // Every entry of this tab would move: the tab would end
                    // empty and vanilla would auto-hide it. Never clear an
                    // original tab - keep all of it untouched and say so.
                    if (TabConfig.get().debugLogging) {
                        AutoModTabs.LOGGER.info("[AutoModTabs] {} would lose every entry ({}), kept intact - "
                            + "its items stay accessible both here and in the generated tabs", tabId, parents.size());
                    } else {
                        AutoModTabs.LOGGER.debug("[AutoModTabs] {} would lose every entry ({}), kept intact", tabId, parents.size());
                    }
                } else {
                    for (ItemStack entry : removable) {
                        // Disabled by contract: a live destination is already
                        // guaranteed by the predicates above, so removal
                        // cannot hide the item.
                        event.remove(entry, CreativeModeTab.TabVisibility.PARENT_TAB_ONLY);
                    }
                    if (TabConfig.get().debugLogging) {
                        AutoModTabs.LOGGER.info("[AutoModTabs] moved {} of {} entries out of {} (not emptied)",
                            removable.size(), parents.size(), tabId);
                    }
                }
            }
        }

        if (stripSearchPlain || stripSearchVariants) {
            // Independent search-side strip: no would-empty guard needed (a
            // tab's search list being empty is not a structural change) and
            // every predicate below still requires a live generated
            // destination.
            for (ItemStack entry : List.copyOf(event.getSearchEntries())) {
                boolean plain = stripSearchPlain && isPlainMovedItem(event, entry);
                boolean variant = stripSearchVariants && isCustomizedRecipeVariant(entry);
                if (plain || variant) {
                    event.remove(entry, CreativeModeTab.TabVisibility.SEARCH_TAB_ONLY);
                }
            }
        }
    }

    /** Entry is a default-instance stack of an item one of our generated tabs shows. */
    private static boolean isPlainMovedItem(BuildCreativeModeTabContentsEvent event, ItemStack entry) {
        if (!Discovery.ourGeneratedItems(event.getParameters()).contains(entry.getItem())) {
            return false;
        }
        try {
            ItemStack def = entry.getItem().getDefaultInstance();
            DataComponentMap mine = entry.getComponents();
            return def.getComponents().equals(mine);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Entry matches (item + complete components) a customized recipe output of
     * an included recipe source - i.e. a variant that actually has a generated
     * recipe tab to move into under the <i>current</i> config. Sources excluded
     * right now (via {@code excludeMods} or {@code includeDatapackRecipes})
     * pass this false, so their customized variants stay untouched in foreign
     * tabs: nothing is removed unless its destination really shows it.
     */
    private static boolean isCustomizedRecipeVariant(ItemStack entry) {
        for (RecipeEntry recipeEntry : RecipeIndex.allEntries()) {
            if (!recipeEntry.customized()) {
                continue;
            }
            if (!RecipeIndex.isSourceIncluded(recipeEntry.source())) {
                continue; // no live destination for this source
            }
            if (recipeEntry.sameVariant(entry)) {
                return true;
            }
        }
        return false;
    }
}
