package com.automodtabs.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.neoforge.client.gui.CreativeTabsScreenPage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Watchpoints for the optional Fabric creative-screen navigation sync
 * ({@code syncFabricCreativeTabNavigation}, default off).
 *
 * <p><b>The problem being solved.</b> On NeoForge 26.3 + Fabric API through
 * Fabricated-NeoForge, two independent pagination systems live on the same
 * {@link CreativeModeInventoryScreen}:
 *
 * <ul>
 * <li>NeoForge's own: private {@code pages} list + public
 * {@code getCurrentPage()}/{@code setCurrentPage(...)}, with its
 * {@code <} / {@code >} buttons above the GUI (added in NeoForge's patch of
 * {@code init()}).</li>
 * <li>Fabric API's {@code CreativeModeInventoryScreenMixin}: a static
 * {@code @Unique int currentPage} with a second {@code <} / {@code >} pair
 * inside the GUI, and its {@code isTabVisible(tab)} gate that cancels
 * {@code extractTabButton} / {@code checkTabClicked} / tooltips for every tab
 * whose Fabric page does not match its own index (Fabric assigns the 14
 * vanilla tabs to its page 0 and groups all mod tabs, NeoForge or Fabric,
 * 10-per-page from its own sort order - see
 * {@code CreativeModeTabsMixin.paginateTabs}).</li>
 * </ul>
 *
 * <p>Each control only drives its own state, so when the two indices disagree
 * the Fabric gate hides tabs the NeoForge control says are on screen, tabs
 * vanish or appear empty depending on which control you clicked, and two
 * page counters show different fractions. This mixin adds the two watch
 * points the sync needs (all runtime decisions live in
 * {@link com.automodtabs.NavigationBridgeHooks}, which no-ops unless
 * {@code FabricCompat.fabricCreativeTabs()} says Fabric API is actually
 * present <i>and</i> the user turned the sync on):
 *
 * <ol>
 * <li>{@code setCurrentPage} TAIL - fires when the user clicks the
 * <em>NeoForge</em> controls; the bridge votes on the best-matching Fabric
 * page and calls Fabric's public {@code switchToPage(int)}.</li>
 * <li>{@code extractTabButton} HEAD - fires every frame for each rendered tab
 * button; the bridge polls Fabric's {@code getCurrentPage()} and, when it
 * changed (the user clicked the <em>Fabric</em> controls or the N/K keys),
 * mirrors it onto NeoForge's page.</li>
 * </ol>
 *
 * <p>Both handlers swallow and debug-log any Throwable so the optional sync
 * can never break the creative screen. There are no Fabric classes referenced
 * here - Fabric API is only used through its public
 * {@code FabricCreativeModeInventoryScreen} duck interface inside the bridge,
 * whose class resolution stays lazy, and the whole config is additionally
 * gated with {@code requiredMods=["fabric_api"]} in
 * {@code neoforge.mods.toml} so a plain NeoForge install never applies it at
 * all.
 */
@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeScreenNavigationMixin {

    @Inject(method = "setCurrentPage", at = @At("TAIL"))
    private void automodtabs$afterNeoForgePageChange(CreativeTabsScreenPage currentPage, CallbackInfo ci) {
        try {
            com.automodtabs.NavigationBridgeHooks.afterNeoForgePageChange(
                (CreativeModeInventoryScreen) (Object) this, currentPage);
        } catch (Throwable t) {
            com.automodtabs.AutoModTabs.LOGGER.debug("[AutoModTabs] navigation sync watchpoint (NeoForge) threw: {}",
                t.toString());
        }
    }

    @Inject(method = "extractTabButton", at = @At("HEAD"))
    private void automodtabs$pollFabricPage(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CreativeModeTab tab,
        CallbackInfo ci) {
        try {
            com.automodtabs.NavigationBridgeHooks.poll((CreativeModeInventoryScreen) (Object) this);
        } catch (Throwable t) {
            com.automodtabs.AutoModTabs.LOGGER.debug("[AutoModTabs] navigation sync watchpoint (poll) threw: {}",
                t.toString());
        }
    }
}
