package com.automodtabs;

import java.lang.reflect.Field;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import org.jspecify.annotations.Nullable;

/**
 * Forces vanilla creative tab contents to rebuild so live config edits apply
 * immediately, without rejoining a world or restarting the game.
 *
 * <p><b>How vanilla caches.</b> {@code CreativeModeTabs} memoises the last
 * {@code ItemDisplayParameters} it built contents with in the private static
 * {@code CACHED_PARAMETERS} field. {@code tryRebuildTabContents}
 * (a <i>public static</i> vanilla method on 26.3) informs it if any record
 * field is unchanged - it returns {@code false} without rebuilding.
 * {@link #requestRebuild()} forces a rebuild with two independent mechanisms, so
 * at least one of them always works:
 *
 * <ol>
 * <li><b>Cache clear (reflection).</b> {@code CACHED_PARAMETERS} is nulled
 * reflectively, putting vanilla back into its "never built yet" state. Best
 * effort: on Java module systems that prevent deep reflection the attempt logs
 * a warning and mechanism #2 still covers it.</li>
 * <li><b>Parameter wrapper (public API, no reflection).</b>
 * {@code tryRebuildTabContents} is invoked right here with a wrapper
 * {@link HolderLookup.Provider} that delegates everything to the real one but
 * is a fresh object. {@code ItemDisplayParameters#needsUpdate} holds
 * {@code holders != holders} unpaid-identity, so the fresh wrapper is always
 * "an update", which forces a full rebuild of every tab's contents and re-caches
 * the wrapper. The next screen-open rebuild (with the real provider) then
 * rebuilds once more and settles the cache - a harmless extra pass. All our
 * generated tabs derive their contents from the live config on every pass, so
 * config changes apply during the very next creative screen update.</li>
 * </ol>
 *
 * <p>Reflection details: NeoForge 26.x ships mojmap runtime names, so the field
 * name {@code CACHED_PARAMETERS} resolves both in dev and production. The field
 * is nullable by design and {@code null} is exactly the fresh-cache state
 * vanilla itself ships in. Only the client (render) thread calls it - same
 * thread as vanilla's own rebuild - so no extra synchronisation is needed.
 */
public final class TabRebuild {
    private TabRebuild() {
    }

    /** Requests a full tab-contents rebuild with the current config applied. */
    public static void requestRebuild() {
        Discovery.invalidateCaches();
        boolean cleared = clearVanillaParametersCache();
        boolean rebuilt = rebuildNow();
        if (!cleared && !rebuilt) {
            AutoModTabs.LOGGER.info("[AutoModTabs] no in-world rebuild possible right now - "
                + "tab contents will refresh when a world/creative screen is next opened");
        }
    }

    /**
     * Reflectively clears {@code CreativeModeTabs.CACHED_PARAMETERS}.
     * Returns {@code true} when the field was actually set; any failure (name,
     * module system, security) logs at WARN and returns {@code false}.
     */
    private static boolean clearVanillaParametersCache() {
        try {
            Field field = CreativeModeTabs.class.getDeclaredField("CACHED_PARAMETERS");
            field.setAccessible(true);
            field.set(null, null);
            AutoModTabs.LOGGER.info("[AutoModTabs] cleared CreativeModeTabs.CACHED_PARAMETERS");
            return true;
        } catch (Throwable t) {
            AutoModTabs.LOGGER.warn("[AutoModTabs] could not clear CreativeModeTabs.CACHED_PARAMETERS "
                + "(the parameter-wrapper rebuild still applies the change): {}", t.toString());
            return false;
        }
    }

    /**
     * Public-API rebuild: calls {@code CreativeModeTabs.tryRebuildTabContents}
     * with a one-off wrapped {@link HolderLookup.Provider} so the cached
     * parameters are guaranteed to count as outdated. Returns {@code true} if
     * the current parameters enabled a rebuild (needs a loaded world; at the
     * title screen the cache-clear above handles it at the next world join).
     */
    private static boolean rebuildNow() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null || mc.getConnection() == null) {
                return false; // not in a world yet - nothing to rebuild into
            }
            FeatureFlagSet flags = mc.player.connection.enabledFeatures();
            boolean hasPermissions = mc.player.canUseGameMasterBlocks();
            HolderLookup.Provider real = mc.level.registryAccess();
            HolderLookup.Provider wrapper = new HolderLookup.Provider() {
                @Override
                public Stream<ResourceKey<? extends Registry<?>>> listRegistryKeys() {
                    return real.listRegistryKeys();
                }

                @Override
                public <T> java.util.Optional<? extends HolderLookup.RegistryLookup<T>> lookup(
                    ResourceKey<? extends Registry<? extends T>> key) {
                    return real.lookup(key);
                }
            };
            boolean rebuilt = CreativeModeTabs.tryRebuildTabContents(flags, hasPermissions, wrapper);
            if (!rebuilt) {
                int size = BuiltInRegistries.ITEM.size();
                AutoModTabs.LOGGER.debug("[AutoModTabs] public-API rebuild passed no-op (items: {})", size);
            }
            return rebuilt;
        } catch (Throwable t) {
            AutoModTabs.LOGGER.warn("[AutoModTabs] public-API tab rebuild failed: {}", t.toString());
            return false;
        }
    }
}
