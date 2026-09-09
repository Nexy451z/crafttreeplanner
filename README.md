# CraftTreePlanner

A crafting tree planner mod for **Minecraft 1.21.1** with **NeoForge 21.1.x**.
Hover over any item and open a full crafting tree (AE2-style): it shows what you already own, what you need to craft, and what you are missing — across your inventory, Refined Storage, and Applied Energistics 2. Then craft everything with one click.

![Mod status: alpha](https://img.shields.io/badge/status-alpha-orange) ![Minecraft: 1.21.1](https://img.shields.io/badge/minecraft-1.21.1-green) ![NeoForge: 21.1.x](https://img.shields.io/badge/neoforge-21.1.x-blue) ![License: MIT](https://img.shields.io/badge/license-MIT-yellow)

## Features

- **Crafting tree GUI** — bottom-up recipe tree for the hovered/held item, including intermediate steps
- **Stock-aware planning** — counts your inventory, Refined Storage grid contents, and AE2 ME storage as one shared stock pool; badges show `Stock / Craft / Missing` per node
- **Multi-station support** — recipes are found via JEI for *all* machine types (furnace, blast furnace, smoker, stonecutter, smithing, and modded machines like alloy smelters); click the station box to pick a method, then a specific recipe within it
- **Workstation slot** — pin a preferred machine; recipes from that station are prioritized in both planning and execution
- **One-click direct crafting** — pulls ingredients straight from your inventory (and the open RS grid), crafts all intermediate steps on the server, and hands you the result; failed steps roll back everything
- **Configurable search limits** — 4 presets (light / normal / large / huge) for depth, node count and time budget; searches run on a background thread with a live progress bar; further tuning via `config/crafttreeplanner-client.toml`
- **Recipe viewer hooks** — left click / `R` opens recipes, right click / `U` opens usages (uses your JEI/REI keybinds)
- **Cycle & runaway protection** — self-feeding recipes, compression-block "reverse crafting" loops, deep recursion and huge modpacks are all bounded (search budget, node cap, per-lookup timeout with an O(1) vanilla recipe index + session-wide candidate cache)
- **Languages** — English and Japanese (`en_us` / `ja_jp`)

## Usage

 1. Hover an item in any inventory / terminal / recipe viewer (or hold it in your main hand) and press **C** (rebindable, `Open Crafting Tree`).
 2. Set the desired amount (input box, `+/-`, or `x1/x10/x64` presets). The tree recomputes in the background (progress bar at the bottom).
 3. Optional: set a **workstation** (bottom-left slot) to prefer a specific machine.
 4. Press **Create** (header) or **Craft All** (footer) — both run *direct craft*: ingredients are pulled from your inventory + the open RS grid, all steps run server-side, result appears in the **Result slot** (click to collect). Nothing is consumed if a step fails.
 5. Left-click an item icon to see its recipe in JEI/REI, right-click for usages.

> **Note:** RS/AE2 stock is read from the *currently open* terminal screen. Open your RS grid or ME terminal while planning to include network storage.

## Supported integrations (all optional at runtime)

| Mod | What it adds |
|-----|--------------|
| JEI | Machine-agnostic recipe search, R/U keybinds, recipe/usage windows |
| REI | Same hooks via reflection (works without JEI) |
| Refined Storage 2 | Grid stock counts, ingredient extraction (compiled against RS 2.0.9, tested with 2.0.9) |
| Applied Energistics 2 | ME terminal stock counts + "autocraftable" badge (compiled against AE2 19.2.17) |

No hard dependency: the mod runs fine with JEI/REI/RS/AE2 absent (fallback to the vanilla `RecipeManager`).
JEI, Refined Storage and AE2 are **compile-time** integrations (typed API calls guarded at runtime by mod-presence checks); REI still uses reflection. One known internal detail: Refined Storage's grid menu keeps its `Grid` field private with no public accessor, so that single field is read reflectively (typed + guarded).

**Modded machine recipes** (Mekanism, Create, Oritech, Industrial Foregoing, Farmers Delight, Mystical Agriculture, AE2 pattern-style, etc.) are executed server-side through a generic path: inputs are read from the recipe (vanilla `getIngredients`, reflection over `Ingredient` fields, or custom input APIs like Mekanism's `ItemStackIngredient`), and outputs from `getResultItem` or a generic discovery over the recipe object's own output fields/methods — all server-side data, no client-trusted output minting. Recipes whose inputs include non-item resources (gases, fluids) are skipped.

## Building

Requirements: **JDK 21**.

```powershell
./gradlew build
```

The mod jar is produced in `build/libs/`. 

JEI is compiled against the official **API artifacts** published on [maven.blamejared.com](https://maven.blamejared.com/) — no local jar needed. Pin the version in `gradle.properties`:

```properties
jei_version=19.52.0.422
```

Refined Storage is pinned the same way (full mod jar from [maven.creeperhost.net](https://maven.creeperhost.net/), the same repository Refined Storage's own addon projects use):

```properties
refinedstorage_version=2.0.9
```

Applied Energistics 2 the same way (full mod jar from Maven Central):

```properties
ae2_version=19.2.17
```

To test inside the dev client with JEI installed, the `localRuntime` dependency fetches the full JEI jar automatically (`./gradlew runClient`).

## Install (players)

1. Install [NeoForge](https://neoforged.net/) 21.1.235+ for Minecraft 1.21.1
2. Drop the built jar (`crafttreeplanner-<version>.jar`) into your `mods` folder
3. Optional: add JEI / Refined Storage / AE2 for the full experience

## Roadmap / Known limitations

- Server-side direct crafting trusts the step list sent by the client; server-side re-validation (recipe existence + output match) is planned
- RS/AE2 stock only visible while the respective terminal screen is open
- Alternative-recipe switching re-resolves only the affected subtree

## License

MIT

