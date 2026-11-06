package com.automodtabs;

/**
 * Which registered items end up in the generated tabs.
 *
 * <p>The three modes differ only in what is <i>excluded</i>; every eligible
 * item still goes through the mod/item exclusion filters:
 *
 * <ul>
 * <li>{@link #ALL} - every eligible registered item, no matter whether some
 * other creative tab already displays it. This is the default and matches the
 * legacy {@code includeHiddenItems: true}.</li>
 * <li>{@link #MISSING_ONLY} - only items that <b>do not appear in any other
 * creative tab</b>. Mods that ship their own tabs mostly disappear from the
 * generated ones; items from tab-less mods are still uncovered.</li>
 * <li>{@link #HIDDEN_ONLY} - a stricter subset of {@link #MISSING_ONLY}: only
 * items absent from every other tab <i>and</i> never produced by any recipe we
 * could scan. These are the "invisible" items that normal modding usually
 * leaves unreachable.</li>
 * </ul>
 *
 * <p>{@code MISSING_ONLY} and {@code HIDDEN_ONLY} are defined through the set
 * of items visible in the tabs built by other mods and vanilla; that set is
 * (re-)computed during each tab-rebuild cycle.
 */
public enum InclusionMode {
    ALL,
    MISSING_ONLY,
    HIDDEN_ONLY;

    /**
     * Parses a config value; unknown or missing strings fall back to
     * {@link #ALL} and are reported instead of crashing the loader.
     */
    public static InclusionMode parse(@org.jspecify.annotations.Nullable String raw) {
        if (raw != null) {
            for (InclusionMode mode : values()) {
                if (mode.name().equalsIgnoreCase(raw.trim())) {
                    return mode;
                }
            }
        }
        if (raw != null && !raw.isBlank()) {
            AutoModTabs.LOGGER.warn("[AutoModTabs] unknown inclusionMode '{}' in config, using ALL", raw);
        }
        return ALL;
    }
}
