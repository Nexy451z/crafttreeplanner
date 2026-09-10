# NexCraftTree

An AE2-style crafting tree planner for NeoForge. Hover an item, press `C`, and see the full crafting tree with your stock (inventory / Refined Storage / AE2 / Sophisticated Backpacks), missing materials, alternative crafting methods and one-click direct crafting.

## Supported versions

| Branch | Minecraft | NeoForge | JEI | Refined Storage | AE2 |
|---|---|---|---|---|---|
| `main` | 1.21.1 | 21.1.x | 19.x | 2.0.9 | 19.2.17 |
| `port/26.1.2` | 26.1.2 | 26.1.2.x | 29.x | 3.2.1 | 26.1.10-beta |

## Features

- **Crafting tree view** — recursively expands ingredients from the required amount and color-codes stock / craftable / missing. Intermediate crafts are expanded too.
- **Stock aggregation** — counts your inventory (including offhand and equipment), Refined Storage, AE2 (while its screen is open) and the contents of **Sophisticated Backpacks**.
- **Alternative crafting methods** — shows crafting table, furnace, and mod machine candidates. Crafting-table recipes come first when available; for ingots / gems / dusts (tags such as `c:ingots`, `c:gems`, `c:dusts`) smelting is selected automatically.
- **One-click direct crafting** — pulls ingredients from inventory / RS / backpacks, including intermediate crafts. The server re-validates recipes, stations and execution counts, and rolls back precisely per execution on failure.
- **Recipe viewer integration** — open JEI / REI recipes (R / left-click) and uses (U / right-click) from items in the tree.
- **12 languages** — ja_jp / en_us / de_de / fr_fr / es_es / it_it / ru_ru / zh_cn / zh_tw / zh_hk / ko_kr / pt_br.

## Usage

1. Hover an item (inventory, JEI, etc.) and press `C`.
2. Inspect missing materials and alternative methods in the tree (left-click item: recipes / right-click: uses).
3. Pick another method from the station popup or the workstation slot.
4. Use **Craft** / **Craft All** for direct crafting (the output appears in the output slot).

The key binding can be changed in Options → Controls (category: **NexCraftTree**).

## Requirements

- **NeoForge** (see the version table above).
- JEI / REI / Refined Storage / AE2 / Sophisticated Backpacks are all **optional**. Missing integrations are skipped silently.

## Build

```powershell
.\gradlew.bat build
# output: build\libs\nexcrafttree-<version>.jar
```

## License

MIT License — see [LICENSE](LICENSE).
