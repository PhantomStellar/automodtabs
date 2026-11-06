package com.automodtabs;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.neoforge.client.gui.CreativeTabsScreenPage;
import net.fabricmc.fabric.api.client.creativetab.v1.FabricCreativeModeInventoryScreen;
import com.automodtabs.mixin.NeoForgePagesAccessor;
import org.jspecify.annotations.Nullable;

/**
 * Runtime half of the optional creative-screen navigation sync
 * ({@code syncFabricCreativeTabNavigation}). See
 * {@link com.automodtabs.mixin.CreativeScreenNavigationMixin} for why two
 * independent pagination states exist on NeoForge 26.3 + Fabric API
 * (Fabricated-NeoForge) and where the watch points come from.
 *
 * <p><b>Mapping between the two page systems.</b> The two states cannot be
 * equated by index: NeoForge chunks <i>all</i> displayable tabs by its own
 * sort order into pages of 10, while Fabric API pins the 14 vanilla tabs to
 * its page 0 and chunks every mod tab (NeoForge or Fabric origin) 10-per-page
 * from its own namespace-first sort order. A stable deterministic mapping is
 * therefore built by <i>majority vote</i>:
 *
 * <ul>
 * <li>NeoForge &rarr; Fabric: count the Fabric page index of every
 * non-common tab on the NeoForge page just selected; the most common Fabric
 * page index wins and Fabric's public
 * {@code FabricCreativeModeInventoryScreen#switchToPage(int)} is called.
 * {@code hasGroupForPage} inside Fabric rejects impossible numbers, so this
 * is safe by construction.</li>
 * <li>Fabric &rarr; NeoForge: poll Fabric's page index each frame (the click
 * itself happens inside Fabric's mixin, no callback exists); when it changed,
 * count how many tabs of each NeoForge page belong to the new Fabric page and
 * set the majority NeoForge page via the public
 * {@code setCurrentPage(...)}.</li>
 * </ul>
 *
 * <p>Fabric's "common" row tabs (search/inventory/hotbar/OP, see decompiled
 * {@code FabricCreativeGuiComponents.COMMON_TABS}) are excluded from the
 * votes: Fabric reports {@code getPage(common) == its current page}, which
 * would make every vote self-confirming.
 *
 * <p><b>Loop safety.</b> The two directions terminate by construction: a
 * Fabric {@code switchToPage} to its own current page returns {@code false}
 * (see the decompiled implementation), and the NeoForge direction only runs
 * when the Fabric index actually changed from the remembered one. Both watch
 * points additionally share an {@code inSync} flag that suppresses any nested
 * re-entry (the poll sets a NeoForge page, whose {@code setCurrentPage} TAIL
 * watch point would otherwise immediately try to push back into Fabric).
 * Cycle length is therefore bounded and each state change happens at most
 * once per frame.
 *
 * <p><b>Gating.</b> {@link #active()} requires
 * {@link FabricCompat#fabricCreativeTabs()} <b>and</b>
 * {@code TabConfig.get().syncFabricCreativeTabNavigation} (default
 * {@code false}) on every entry point, so with the setting off or without
 * Fabric API this class is dead code: no interface cast, no vote, no call.
 * {@code FabricCreativeModeInventoryScreen} is Fabric's published duck
 * interface; the {@code instanceof} is only executed after the active() gate
 * (lazy constant-pool resolution), so the critical bit - loading a Fabric
 * class - cannot happen without Fabric API.
 *
 * <p>All entry points catch {@link Throwable}: an optional compatibility
 * feature must never take the creative screen down.
 */
public final class NavigationBridgeHooks {
    private NavigationBridgeHooks() {
    }

    /**
     * Fabric's common-row tab keys (decompiled from
     * {@code FabricCreativeGuiComponents.COMMON_TABS}, Fabric API
     * fabric-creative-tab-api-v1 5.0.22 for 26.3). Fabric treats these
     * specially in {@code getPage(tab)}; they must not vote.
     */
    private static final java.util.Set<ResourceKey<CreativeModeTab>> FABRIC_COMMON_TABS = java.util.Set.of(
        CreativeModeTabs.SEARCH, CreativeModeTabs.INVENTORY, CreativeModeTabs.HOTBAR, CreativeModeTabs.OP_BLOCKS);

    /**
     * Suppresses nested watch points: set while one direction is mirroring,
     * so if that side's setter triggers the other watch point within the same
     * call, it is skipped. Both watch points run on the render thread.
     */
    private static boolean inSync;

    /**
     * The Fabric page index seen at the previous poll; the Fabric &rarr;
     * NeoForge direction only reacts when this differs, so the work happens
     * once per actual Fabric page change, not per frame.
     */
    private static int lastSeenFabricPage = -1;

    /**
     * True when the sync feature may act: Fabric API creative-tab environment
     * detected AND the user turned it on. Cheap enough to call per render
     * frame (two field reads).
     */
    private static boolean active() {
        return FabricCompat.fabricCreativeTabs() && TabConfig.get().syncFabricCreativeTabNavigation;
    }

    // ------------------------------------------------------------------
    // Watch point: NeoForge page changed (NeoForge < / > buttons)
    // ------------------------------------------------------------------

    /**
     * Called from the {@code setCurrentPage} TAIL watch point. Votes on the
     * Fabric page matching the tabs of the newly selected NeoForge page and
     * switches Fabric over if that differs from Fabric's current page.
     */
    public static void afterNeoForgePageChange(CreativeModeInventoryScreen screen, CreativeTabsScreenPage page) {
        if (inSync || !active()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() != screen) {
            return; // a stale page object click on a screen that already went away
        }
        inSync = true;
        try {
            FabricCreativeModeInventoryScreen fabric = asFabric(screen);
            if (fabric == null) {
                return;
            }
            int target = voteFabricPage(fabric, page.getVisibleTabs());
            if (target < 0) {
                return;
            }
            boolean changed = fabric.switchToPage(target);
            if (changed) {
                AutoModTabs.LOGGER.info("[AutoModTabs] navigation sync: NeoForge page selected -> Fabric page {} ({})",
                    target, describeTabIndex(fabric));
            }
        } catch (Throwable t) {
            AutoModTabs.LOGGER.debug("[AutoModTabs] navigation sync (NeoForge -> Fabric) failed: {}", t.toString());
        } finally {
            inSync = false;
        }
    }

    // ------------------------------------------------------------------
    // Watch point: per-frame poll (Fabric < / > buttons and N/K keys)
    // ------------------------------------------------------------------

    /**
     * Called from the {@code extractTabButton} HEAD watch point (every frame,
     * per rendered tab button). Reads Fabric's page index and, when it
     * changed since the previous poll, mirrors it onto NeoForge's page.
     */
    public static void poll(CreativeModeInventoryScreen screen) {
        if (inSync || !active()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() != screen) {
            return;
        }
        try {
            FabricCreativeModeInventoryScreen fabric = asFabric((CreativeModeInventoryScreen) screen);
            if (fabric == null) {
                return;
            }
            int fabricIndex = fabric.getCurrentPage();
            if (fabricIndex == lastSeenFabricPage) {
                return; // nothing changed since the last look
            }
            lastSeenFabricPage = fabricIndex;
            inSync = true;
            try {
                List<CreativeTabsScreenPage> pages = ((NeoForgePagesAccessor) screen).automodtabs$getPages();
                if (pages == null || pages.isEmpty()) {
                    return;
                }
                int best = bestNeoForgePageForFabric(fabric, pages, fabricIndex);
                if (best < 0) {
                    return;
                }
                CreativeTabsScreenPage target = pages.get(best);
                if (screen.getCurrentPage() != target) {
                    screen.setCurrentPage(target);
                    AutoModTabs.LOGGER.info("[AutoModTabs] navigation sync: Fabric page {} -> NeoForge page {}/{}",
                        fabricIndex, best + 1, pages.size());
                }
            } finally {
                inSync = false;
            }
        } catch (Throwable t) {
            AutoModTabs.LOGGER.debug("[AutoModTabs] navigation sync (Fabric -> NeoForge) failed: {}", t.toString());
        }
    }

    // ------------------------------------------------------------------
    // Voting helpers
    // ------------------------------------------------------------------

    /**
     * The Fabric page index that best matches the tabs of the NeoForge page
     * (majority vote over Fabric-page counts), or {@code -1} when no tab has
     * a usable Fabric page or everything on this page is Fabric's common row.
     */
    private static int voteFabricPage(FabricCreativeModeInventoryScreen fabric, List<CreativeModeTab> tabs) {
        HashMap<Integer, Integer> votes = new HashMap<>();
        for (CreativeModeTab tab : tabs) {
            if (isFabricCommonTab(tab)) {
                continue;
            }
            int page = safeFabricPage(fabric, tab);
            if (page >= 0) {
                votes.merge(page, 1, Integer::sum);
            }
        }
        return bestVote(votes);
    }

    /**
     * The index into {@code pages} of the NeoForge page holding the most tabs
     * that Fabric currently shows on {@code fabricIndex}, or {@code -1} when
     * no NeoForge page matches.
     */
    private static int bestNeoForgePageForFabric(FabricCreativeModeInventoryScreen fabric,
        List<CreativeTabsScreenPage> pages, int fabricIndex) {
        int best = -1;
        int bestCount = 0;
        for (int i = 0; i < pages.size(); i++) {
            int count = 0;
            for (CreativeModeTab tab : pages.get(i).getVisibleTabs()) {
                if (isFabricCommonTab(tab)) {
                    continue;
                }
                if (safeFabricPage(fabric, tab) == fabricIndex) {
                    count++;
                }
            }
            if (count > bestCount) {
                best = i;
                bestCount = count;
            }
        }
        return best;
    }

    /** Highest vote (ties resolved by the smaller page index, deterministic). */
    private static int bestVote(HashMap<Integer, Integer> votes) {
        int best = -1;
        int bestCount = 0;
        for (var entry : votes.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best;
    }

    /**
     * Fabric's page index for one tab; {@code -1} when that tab has none
     * ({@code fabric_getPage()} throws for a not-yet-paginated tab, and a
     * non-mixin tab would fail the cast anyway). Fabric's common-row tabs are
     * always counted as their current page, so they are excluded earlier.
     */
    private static int safeFabricPage(FabricCreativeModeInventoryScreen fabric, CreativeModeTab tab) {
        try {
            return fabric.getPage(tab);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * True when {@code tab} is one of Fabric's common-row tabs (search /
     * inventory / hotbar / OP). Fabric reports {@code getPage(common)} as its
     * <i>current</i> page, so letting them vote would confirm whatever the
     * current state is.
     */
    private static boolean isFabricCommonTab(CreativeModeTab tab) {
        return BuiltInRegistries.CREATIVE_MODE_TAB.getResourceKey(tab)
            .map(FABRIC_COMMON_TABS::contains)
            .orElse(false);
    }

    /**
     * Fabric's published duck interface on the creative screen; null when
     * Fabric API's screen mixin is absent (its
     * {@code FabricCreativeModeInventoryScreen} implementation is added by
     * their mixin), which doubles as the last line of defence for class
     * loading safety.
     */
    private static @Nullable FabricCreativeModeInventoryScreen asFabric(CreativeModeInventoryScreen screen) {
        if (screen instanceof FabricCreativeModeInventoryScreen duck) {
            return duck;
        }
        return null;
    }

    /** One-line debug helper for the sync direction logs. */
    private static String describeTabIndex(FabricCreativeModeInventoryScreen fabric) {
        return String.format(Locale.ROOT, "page %d/%d", fabric.getCurrentPage() + 1, fabric.getPageCount());
    }
}
