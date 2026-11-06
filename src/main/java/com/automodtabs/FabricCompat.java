package com.automodtabs;

import net.neoforged.fml.ModList;
import org.jspecify.annotations.Nullable;

/**
 * Detects at runtime whether this NeoForge install runs Fabric mods (through
 * Fabricated-NeoForge or a similar bridge) or has the Fabric API available -
 * <b>without</b> ever loading Fabric classes, so a plain NeoForge install with
 * no Fabric anything never sees a missing-class error and uses the unchanged
 * NeoForge paths.
 *
 * <p><b>Why this exists.</b> Fabricated-NeoForge runs Fabric mods on NeoForge by
 * loading the Fabric API's own mixins. The Fabric creative-tab module
 * ({@code fabric-creative-tab-api-v1}, present on Fabric API 0.165.2+26.3) mixes
 * {@code CreativeModeTabMixin} into {@code CreativeModeTab} and
 * {@code CreativeModeTabsMixin} into {@code CreativeModeTabs}; the former
 * <i>recomputes and replaces every tab's content list</i> at the TAIL of
 * {@code buildContents} - after both our generated tabs and any foreign tab have
 * filled their entries. AutoModTabs therefore keeps a low profile in such an
 * environment: it avoids forcing foreign tabs to rebuild mid-pass (their
 * contents would have been recomputed with Fabric callbacks attached and can be
 * mid-build when we touch them) and reports the environment it runs in.
 *
 * <p>Detection has three states, in increasing "Fabric-ness":
 * <ol>
 * <li>{@link Mode#PLAIN_NEOFORGE} - no Fabric API modules loaded. Default,
 * unchanged v0.2/v0.3 behaviour (aggressive tab inspection etc.).</li>
 * <li>{@link Mode#FABRIC_API} - Fabric API modules are on the mod list but no
 * Fabric bridge ({@code fabricated}) is. Rare; happens with hybrid loaders.</li>
 * <li>{@link Mode#FABRICATED_NEOFORGE} - Fabricated-NeoForge (or a
 * compatible bridge mod, mod id {@code fabricated}) is loaded, so Fabric mods
 * including Fabric-based creative tabs run on this NeoForge instance.</li>
 * </ol>
 */
public final class FabricCompat {
    /** Mod ids any Fabric API creative-tab module can appear under. */
    private static final String[] FABRIC_API_MOD_IDS = {
        "fabric_api", "fabric-api", "fabric_creative_tab_api_v1", "fabric-creative-tab-api-v1"
    };
    /** Mod ids of known Fabric-on-NeoForge bridges. */
    private static final String[] FABRIC_BRIDGE_MOD_IDS = { "fabricated" };

    /** The three possible environments, resolved lazily by {@link #mode()}. */
    public enum Mode {
        PLAIN_NEOFORGE,
        FABRIC_API,
        FABRICATED_NEOFORGE
    }

    private static volatile @Nullable Mode computedMode = null;

    private FabricCompat() {
    }

    /** The detected environment; computed once, then cached. */
    public static Mode mode() {
        Mode state = computedMode;
        if (state == null) {
            synchronized (FabricCompat.class) {
                state = computedMode;
                if (state == null) {
                    state = detect();
                    computedMode = state;
                }
            }
        }
        return state;
    }

    private static Mode detect() {
        ModList list = ModList.get();
        if (list == null) {
            // Not during mod loading probes (tests, datagen). Treated as plain.
            return Mode.PLAIN_NEOFORGE;
        }
        boolean bridge = false;
        for (String id : FABRIC_BRIDGE_MOD_IDS) {
            if (list.isLoaded(id)) {
                bridge = true;
                break;
            }
        }
        if (bridge) {
            return Mode.FABRICATED_NEOFORGE;
        }
        for (String id : FABRIC_API_MOD_IDS) {
            if (list.isLoaded(id)) {
                return Mode.FABRIC_API;
            }
        }
        return Mode.PLAIN_NEOFORGE;
    }

    /** True when Fabric API creative-tab modules (or a bridge that loads them) are present. */
    public static boolean fabricCreativeTabs() {
        return mode() != Mode.PLAIN_NEOFORGE;
    }

    /**
     * True when foreign creative tabs may be force-built during one of our own
     * tab builds ("shown elsewhere" inspection). Force building another tab's
     * contents re-enters the whole NeoForge + Fabric callback stack; in a
     * Fabric API environment the Fabric mixin at the TAIL replaces the foreign
     * tab's content list at the same time, so there we read the foreign tabs
     * as they are - a best-effort, never destructive classification.
     */
    public static boolean canForceBuildForeignTabs() {
        return mode() == Mode.PLAIN_NEOFORGE;
    }

    /** Human-readable one-liner for logs and the discovery report. */
    public static String describe() {
        return switch (mode()) {
            case PLAIN_NEOFORGE -> "NeoForge (no Fabric API)";
            case FABRIC_API -> "NeoForge with Fabric API present";
            case FABRICATED_NEOFORGE -> "NeoForge with Fabric mods through Fabricated-NeoForge";
        };
    }
}
