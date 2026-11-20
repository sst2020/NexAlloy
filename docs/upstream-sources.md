# Upstream patch sources

The fork `gnadgnaoh/SexAlloy` is archived. Its patch families were ported into
this repo as DexKit/Xposed runtime hooks. This document records where each
family actually comes from so future syncs know what to watch and how to port.

Patches in this repo are NOT the upstream Morphe patcher DSL — they are
hand-ported runtime hooks (`findMethodDirect` / `::fp.hookMethod`). Syncing an
upstream change means reading the upstream diff and re-implementing it here,
not copying files.

## Source map

| Local path | Upstream | Notes |
|---|---|---|
| `mrxsin/gmail` | `MrxSiN/GmailHideAds` | Up to date (v2.1.0, no code changes since import) |
| `v4n1x/morphe/soundcloud` | `V4n1X/morphe-patches` | 2025-11-06 commit is an AMOLED resource patch + version metadata; N/A to the runtime port |
| `morphe/twitter` | `crimera/piko` | Recent upstream commits are DSL refactors; ported semantics unchanged |
| `morphe/tiktok` | `hxreborn/hxreborn-tiktok-patches` | No relevant updates since import |
| `morphe/google/discover` | `hxreborn/discover-ads-filter` | Upstream changes are on unported features (share-link, indicator) |
| `hoodles/morphe/protonvpn` | `hxreborn/morphe-patches` | Original `hoo-dles/morphe-patches` is banned/dead; hxreborn is the successor |
| `revanced/zalo`, `revanced/meta` families | — | Fork-author original code, no upstream to sync |
| `morphe/{youtube,music,reddit}` + `morphe-patches`/`morphe-patches-library` submodules | NexAlloy upstream | Submodules track the `nexalloy` branch |

Reference clones of the upstream repos live under `.vibeskills/sources/`
(not committed).

## Porting conventions

- Shared anchors/helpers for ProtonVPN live in
  `hoodles/morphe/protonvpn/shared/` (`Shared.kt`, `FreeAccount.kt`).
- Upstream smali injections map to runtime hooks:
  - `clearFreeUserCheck` → `clearCheckIn(outer, isFreeUser, false)` (scopedHook)
  - `invertFreeServerCheck` → `Method.invertFreeServerCheck`
  - `unlockUserSetting` → same-name helper (ctor arg rewrite + guard keep)
  - `keepUserSetting` → `keepUserSetting` (guard returns its input)
  - `filter/clearConstructorProperty` → same-name helpers (ctor arg rewrite)
  - extension stub classes (`FreeServerLocations`, `ExcludedLocations`,
    `FreeAccount`) → Kotlin objects in `shared/` and the patch file
- Not ported (no faithful runtime equivalent or not yet needed):
  `alwaysTakeLastIfEqz` branch forcing (approximated by clearing the isFreeUser
  check in the same method), `navigationbar/FloatingNavigationBarPatch`
  (~400 lines, UI feature), `theme/*` patches.

## Version caveat

hxreborn's patches target a newer ProtonVPN where entities live under
`com.protonvpn.android.*`; the pre-existing port targets `me.proton.vpn.*`.
Both anchor sets are kept; fingerprints simply fail to match on app versions
that lack the target classes — verify against a real APK when one is
available.
