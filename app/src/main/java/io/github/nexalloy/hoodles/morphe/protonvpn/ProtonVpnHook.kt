package io.github.nexalloy.hoodles.morphe.protonvpn

import io.github.nexalloy.hoodles.morphe.protonvpn.connectionpreferences.UnlockConnectionPreferences
import io.github.nexalloy.hoodles.morphe.protonvpn.customdns.UnlockCustomDns
import io.github.nexalloy.hoodles.morphe.protonvpn.delay.RemoveChangeServerDelay
import io.github.nexalloy.hoodles.morphe.protonvpn.freeservers.ShowFreeServerLocations
import io.github.nexalloy.hoodles.morphe.protonvpn.lan.UnlockLanConnections
import io.github.nexalloy.hoodles.morphe.protonvpn.netshield.UnlockNetShield
import io.github.nexalloy.hoodles.morphe.protonvpn.premium.UnlockVpnPlus
import io.github.nexalloy.hoodles.morphe.protonvpn.profiles.UnlockProfiles
import io.github.nexalloy.hoodles.morphe.protonvpn.splittunneling.UnlockSplitTunneling
import io.github.nexalloy.hoodles.morphe.protonvpn.telemetry.DisableTelemetry
import io.github.nexalloy.hoodles.morphe.protonvpn.upselling.HideUpgradePromotions

val ProtonVpnPatches = arrayOf(
    UnlockVpnPlus,
    ShowFreeServerLocations,
    HideUpgradePromotions,
    RemoveChangeServerDelay,
    DisableTelemetry,
    UnlockCustomDns,
    UnlockLanConnections,
    UnlockSplitTunneling,
    UnlockNetShield,
    UnlockProfiles,
    UnlockConnectionPreferences,
)
