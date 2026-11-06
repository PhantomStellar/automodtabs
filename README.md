# AutoModTabs — Automatic Modded Creative Tabs

A client-side [NeoForge](https://neoforged.net/) mod for **Minecraft 26.3** that automatically organizes items registered by installed mods into dedicated creative inventory tabs — no datapacks, no manual lists, nothing to configure before it works.

- **Minecraft**: 26.3
- **NeoForge**: 26.3.0.99+
- **Side**: Client only (server-safe; multiplayer uses the client's own creative screen)
- **License**: MIT
- **Current version**: 0.5.1

Open the creative inventory after adding any mod, and its items appear in generated tabs named after the mod. Recipe outputs that don't exist as items get tabs too.

## Features

### Generated tabs
- **One tab per mod** or a **single grouped tab** holding everything (`singleGroupedTab`).
- **Three inclusion modes**: `ALL`, `MISSING_ONLY` (only mods without a native tab) and `HIDDEN_ONLY`.
- **Per-mod exclusions** (`excludeGeneratedTabs`, new in 0.5.0): a mod stays fully discovered and processed, but gets no extra generated tab — independent of `excludeMods`. Exclusions apply live, and hidden per-mod tabs are simply absent rather than destroying anything.
- **Alphabetical tab and item sorting** within generated tabs.
- Empty generated tabs register but are auto-hidden by vanilla (creative tabs lock their layout at startup, so hiding is live and reversible).

### Existing tabs left intact
- Original modded tabs are never unregistered or emptied wholesale.
- Optional `removeFromOriginalTabs` moves generated-tab entries out of their original tabs so items are not duplicated; `removeFromSearch` does the same against the search tab independently.
- The vanilla SEARCH tab keeps working (removal is opt-in).

### Recipe discovery
- Scans recipes (including datapack recipes) and creates a **recipe outputs tab** — plus per-mod recipe tabs — for outputs that exist only as recipe results, with customized component variants, grouping by source and deduplication.
- `moveRecipeOutputs` optionally relocates customized output stacks into their source mod's tab (full component-aware matching).

### Configuration screen & keybind
- Full in-game configuration screen, opened with a **rebindable key** (default `J`, category `key.category.automodtabs.openconfig`) — or the usual screens.
- Pages: General, Recipes, Organization, **Mods**, Filters, Compatibility, Diagnostics.
- **Mods page**: lists detected mods with display name, mod id, detected native tab(s), how many of their items show in existing tabs (in-world, with partial-representation flagging), and a live keep/withhold toggle per mod. **15 mods per page**, with a **search box** (mod name or id) and an **"items only" filter** (default on) that hides mods with zero discoverable items so the relevant entries are quick to find. Search and filter change the list view only, never discovery.
- **Manual namespace inclusion** (new in 0.5.0): force a generated tab for any namespace, validated against loaded mods / registered items. Meant for mods whose items register late (see Realistic-Water note below).
- **Live reload**: every content-side change applies without restarting; tab *layout* changes (registering a forced tab) are the documented exception.
- All settings persist in `config/automodtabs.json` (client config folder). Old config files load unchanged; deprecated `showEmptyTabs` is still parsed for compatibility.

### Technical items
- No hardcoded "technical" item patterns since 0.5.0 (`technicalPatterns` defaults to **empty**, `includeTechnicalItems` to **false**).
- Vanilla technical items (barrier, light, command blocks, …) already have their own access path in modern Minecraft; add patterns back yourself only if you want them in generated tabs — explicit `excludeMods`/`excludeItems` always win over technical inclusion.

### Fabricated-NeoForge (experimental, final)
- On NeoForge *with* Fabric API loaded, two independent creative-navigation systems coexist. An opt-in toggle (`syncFabricCreativeTabNavigation`, default **off**) keeps the two page bars roughly in sync. It is **experimental by design**: moving to the last page with one control does not always move the other, so tabs can look inaccessible until you page back using the other bar. This mod does not attempt to merge the two inventory implementations. Plain NeoForge installs are never affected and the mixins require `fabric_api` to even load.
- **Status since 0.5.1: Fabric compatibility here is final.** A notice (log warning + one chat line per session, disablable via `fabricLoaderWarning` / the General page) reports Fabric detection when the game loads. Individual-tab fixes for Fabric loaders are not provided; the supported fallback for late-registering Fabric namespaces is the Mods page's forced-tab entry (restart required). Fabricated-NeoForge behaviour can improve in the loader itself, but this mod will not do further Fabric-compatibility work.

## Realistic Water (Fabric version) — late item registration

Some Fabric-style mods register their items *after* creative tabs are registered. NeoForge creative tabs are structural: once the tab registry freezes at startup, a tab cannot be added later, which is why such mods' items appear in the grouped tab (rebuilt live) but not as a standalone tab while `singleGroupedTab` is off.

v0.5.0's fix is generic, not mod-specific:
1. `forceGeneratedTabs: ["modid"]` (or the Mods-page field) makes a structural tab exist at startup for that namespace even with zero items yet — its items populate once registered.
2. The discovery report names any "latecomer" namespaces (items but no dedicated generated tab) so the situation is visible.
3. Per-mod exclusions and the detector make every other case user-controlled.

## Configuration reference

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Master switch |
| `inclusionMode` | `ALL` | `ALL`, `MISSING_ONLY`, `HIDDEN_ONLY` |
| `excludeMods` | `["minecraft"]` | Mod ids AutoModTabs ignores entirely |
| `excludeItems` | `[]` | Item exclusions; `"id:*"` excludes a whole namespace |
| `technicalPatterns` | `[]` (0.5.0; old configs keep theirs) | Lowercase substrings marking "technical" items |
| `includeTechnicalItems` | `false` | Show technical items in generated tabs (never bypasses exclusions) |
| `excludeGeneratedTabs` | `[]` (new) | Per-mod: no generated tab, everything else proceeds as usual |
| `forceGeneratedTabs` | `[]` (new) | Namespaces forced to get a generated tab (restart to take effect) |
| `fabricLoaderWarning` | `true` (new in 0.5.1) | Notice (log + one chat line per session) when Fabric API / a Fabric bridge is detected; turn off once your loader is known harmless |
| `singleGroupedTab` | `true` | One combined tab instead of per-mod tabs |
| `sortAlphabetically` / `sortTabsAlphabetically` | `true` / `true` | Alphabetical items / tabs |
| `scanRecipes`, `includeRecipeOutputs`, `includeDatapackRecipes`, `includeCustomRecipeStacks`, `groupRecipeOutputsBySource`, `deduplicateRecipeOutputs`, `moveRecipeOutputs` | `true` | Recipe tab behaviour |
| `removeFromOriginalTabs` / `removeFromSearch` | `false` / `false` | Move entries out of original tabs / search tab |
| `debugLogging`, `logDiscovery` | `false` / `false` | Verbose logging |
| `showEmptyTabs` | `false` (deprecated) | Unimplemented on this Minecraft version; parsed for old configs only |

## Building

```
gradlew.bat build
```

Requires Java 25 (the Gradle toolchain fetches the rest). Output: `build/libs/<artifact>-<version>.jar`. The `libs/` folder holds a vendored compile-only reference jar and must stay in the checkout.

## Version history (summary)

- **0.5.1** — Fabric detection notice (disablable), Mods page: 15/page + search + items-only filter; Fabric compatibility declared final.
- **0.5.0** — per-mod tab detection & exclusions, forced-namespace fallback, latecomer reporting, empty technical defaults, Fabric sync explicitly experimental.
- **0.4.1** — grouped-tab dispatch fix, organizer rework, alphabetical tab ordering, config keybind, command/key help cleanups.
- **0.4.0** — in-world rebuilds and report overhaul.
- Earlier — grouped tab, recipe tabs, inclusion modes, config screen, JSON config.

## Known limitations

- Tab *layout* is frozen when the game starts; forced tabs and per-mod tab registration changes need a restart (contents stay live).
- Counts of items shown in existing tabs are computed in-world only (at the title screen data components are not yet bound).
- Fabric navigation sync cannot fully align the two page systems — architectural, not fixable from here.
- The per-mod exclusion empties a generated tab (vanilla then hides it); unregistering is not possible after startup.
