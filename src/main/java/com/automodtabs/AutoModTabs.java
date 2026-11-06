package com.automodtabs;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RecipesReceivedEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point of "Automatic Modded Creative Tabs".
 *
 * <p><b>What it does.</b> A client-only NeoForge 26.3 mod that reads the item
 * registry after registration, groups every registered item by its registry
 * namespace, and registers one creative inventory tab per mod namespace (or a
 * single grouped tab, per config). Items registered by any installed mod -
 * even ones this mod has never seen before - therefore always have a place in
 * the creative inventory, without integration code or item lists. Recipe
 * scanning supplements this: full output ItemStacks (components preserved)
 * are grouped by their recipe id namespace, so datapack-only content such as
 * the Pocket Portals portal items becomes visible too.
 *
 * <p><b>Sides.</b> {@code dist = Dist.CLIENT} keeps this mod off dedicated
 * servers entirely. It registers no payloads and touches no code path the
 * server runs, so joining vanilla or NeoForge dedicated servers without this
 * mod works normally.
 *
 * <p><b>Lifecycle.</b> Configuration loads in the mod constructor, discovery
 * runs in the creative-mode-tab {@link RegisterEvent} (fired after the item
 * registry is complete), and tab <i>contents</i> are derived lazily by vanilla
 * when tab contents are requested, so config changes re-apply on the next
 * rebuild (see {@link TabRebuild}). The {@code BuildCreativeModeTabContentsEvent}
 * listener ({@code TabOrganizer#onBuildTabContents}, posted to every mod bus for
 * every tab) implements the optional move-out filtering of original tabs.
 * Recipe-backed discovery hooks the client event bus and clears its data on
 * disconnect. Live config editing is available through the {@code /automodtabs}
 * commands ({@link ModCommands}) and the in-game config screen
 * ({@link TabConfigScreen}).
 */
@Mod(value = AutoModTabs.MODID, dist = Dist.CLIENT)
public class AutoModTabs {
    public static final String MODID = "automodtabs";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    public AutoModTabs(IEventBus modEventBus, ModContainer container) {
        TabConfig.get().load();

        // Ignores the current enabled flag: the structural tabs always register,
        // their contents follow the live config ({@see Discovery#populate}).
        modEventBus.addListener(TabBuilder::onCreate);
        // Fires for every tab on every rebuild cycle; implements removeFromOriginalTabs / moveRecipeOutputs.
        modEventBus.addListener(TabOrganizer::onBuildTabContents);
        // Recipe discovery lives on the client (main) event bus.
        NeoForge.EVENT_BUS.addListener(RecipeIndex::onRecipesReceived);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> RecipeIndex.clear());
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> DiscoveryReport.tick());
        // /automodtabs command group for live config editing.
        NeoForge.EVENT_BUS.addListener(ModCommands::register);
        // Config keybind (Controls menu shows it under "AutoModTabs").
        modEventBus.addListener(Keybinds::onRegisterKeys);
        NeoForge.EVENT_BUS.addListener(Keybinds::onClientTick);
        // 0.5.1: one-chat-line notice on world join when Fabric API / a Fabric
        // bridge is detected (disablable, see TabConfig#fabricLoaderWarning).
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> fabricNoticeTick());
        // In-game config screen behind the mod list's Config button.
        java.util.function.Supplier<IConfigScreenFactory> configScreenFactory =
            () -> new IConfigScreenFactory() {
                @Override
                public net.minecraft.client.gui.screens.Screen createScreen(ModContainer modContainer,
                    net.minecraft.client.gui.screens.Screen modListScreen) {
                    return new TabConfigScreen(modListScreen);
                }
            };
        container.registerExtensionPoint(IConfigScreenFactory.class, configScreenFactory);
        LOGGER.info("[AutoModTabs] loaded (client only); environment: {}", FabricCompat.describe());
        if (FabricCompat.mode() != FabricCompat.Mode.PLAIN_NEOFORGE && TabConfig.get().fabricLoaderWarning) {
            LOGGER.warn("{}", fabricNoticeText(false));
        }
    }

    /** Shown at most once per game session; the config flag can silence it. */
    private static boolean fabricNoticeShown = false;

    /** Fires the in-chat Fabric notice on the first world join of this session. */
    private static void fabricNoticeTick() {
        if (fabricNoticeShown) {
            return;
        }
        FabricCompat.Mode mode = FabricCompat.mode();
        boolean wanted = TabConfig.get().fabricLoaderWarning;
        if (mode == FabricCompat.Mode.PLAIN_NEOFORGE || !wanted || net.minecraft.client.Minecraft.getInstance().player == null) {
            if (mode == FabricCompat.Mode.PLAIN_NEOFORGE || !wanted) {
                fabricNoticeShown = true; // nothing to show this session; never re-check
            }
            return;
        }
        fabricNoticeShown = true;
        net.minecraft.client.Minecraft.getInstance().player
            .sendSystemMessage(net.minecraft.network.chat.Component.literal(fabricNoticeText(true)));
    }

    /** The notice text; chat variant tells where the setting lives. */
    private static String fabricNoticeText(boolean forChat) {
        String base = "[AutoModTabs] Fabric detected (" + FabricCompat.describe()
            + "): this may affect how the mod works depending on the Fabric loader on NeoForge and how it is implemented. "
            + "Individual-tab fixes for such loaders are NOT provided (for namespaces registering late, use the Mods page: force a generated tab).";
        if (forChat) {
            return base + " If your loader causes no issue, turn this warning off: config screen, General page, "
                + "'Fabric detection notice' (or config/automodtabs.json key fabricLoaderWarning).";
        }
        return base + " Turn off with config option 'fabricLoaderWarning' (General page).";
    }
}
