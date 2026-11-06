package com.automodtabs;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * Client keybind that opens the existing {@link TabConfigScreen} directly
 * (0.4.1 feature). Registered in the dedicated "AutoModTabs" category so it
 * shows up under Controls, works in single player and on multiplayer
 * sessions alike, and is fully independent of the experimental Fabric
 * navigation sync.
 *
 * <p><b>Screen safety:</b> the tick handler opens the screen through
 * {@code Gui#setScreen} (the same transition vanilla uses when closing the
 * inventory from a key), passing the currently open screen (if any) as the
 * parent so "Done" returns where the player came from. If the configuration
 * screen is already open, repeated key presses do nothing - no duplicate
 * instances. Nothing here runs on a dedicated server: the whole mod is
 * {@code Dist.CLIENT}, and key mappings only exist on the client anyway.
 */
public final class Keybinds {
    private Keybinds() {
    }

    /** The Controls-menu category label is the translation key below (en_us.json). */
    public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(
        net.minecraft.resources.Identifier.fromNamespaceAndPath(AutoModTabs.MODID, "openconfig"));

    /** Default: J - unbound in vanilla, well away from the WASD block area. */
    public static final KeyMapping OPEN_CONFIG = new KeyMapping(
        "key.automodtabs.open_config",
        InputConstants.Type.KEYBOARD,
        InputConstants.KEY_J,
        CATEGORY);

    /** Mod-bus registration point. */
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        event.register(OPEN_CONFIG);
    }

    /** Client-tick handling: drains the click counter so no press is lost. */
    public static void onClientTick(ClientTickEvent.Post event) {
        try {
            while (OPEN_CONFIG.consumeClick()) {
                openConfigScreen();
            }
        } catch (Throwable t) {
            AutoModTabs.LOGGER.warn("[AutoModTabs] config keybind failed: {}", t.toString());
        }
    }

    /**
     * Opens {@link TabConfigScreen} over whatever is on screen right now.
     * No-op when the screen is already open (no duplicate instances).
     */
    public static void openConfigScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        if (mc.gui.screen() instanceof TabConfigScreen) {
            return; // already open - pressing again must not stack instances
        }
        mc.gui.setScreen(new TabConfigScreen(mc.gui.screen()));
    }
}
