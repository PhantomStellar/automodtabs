package com.automodtabs.mixin;

import java.util.List;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.neoforged.neoforge.client.gui.CreativeTabsScreenPage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes NeoForge's private pagination state on
 * {@link CreativeModeInventoryScreen} so the optional Fabric navigation sync
 * can map between the two page systems without reflection.
 *
 * <p>NeoForge 26.3's patched creative screen stores its tab-row pages in the
 * private {@code pages} list (built in {@code init()} from
 * {@code CreativeModeTabRegistry.getSortedCreativeModeTabs()} in chunks of
 * 10, see NeoForge docs) with a public
 * {@code getCurrentPage()}/{@code setCurrentPage(...)} pair around it. Only
 * the list itself is unexposed. This accessor is declared in the same
 * {@code automodtabs.mixins.json} as the rest of the navigation sync and is
 * therefore only ever applied when {@code fabric_api} is loaded
 * ({@code requiredMods} in {@code neoforge.mods.toml}), so a plain NeoForge
 * install never even transforms this class.
 */
@Mixin(CreativeModeInventoryScreen.class)
public interface NeoForgePagesAccessor {
    @Accessor("pages")
    List<CreativeTabsScreenPage> automodtabs$getPages();
}
