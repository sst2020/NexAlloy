package io.github.nexalloy.hoodles.morphe.protonvpn.lan

import io.github.nexalloy.*
import io.github.nexalloy.hoodles.morphe.protonvpn.shared.*
import io.github.nexalloy.morphe.*
import io.github.nexalloy.morphe.ResourceType
import io.github.nexalloy.morphe.getResourceId

// Port of hxreborn unlockLanConnectionsPatch.

private const val TITLE_RES = "settings_advanced_allow_lan_title"

private val viewStateConstructor = findMethodDirect { findViewStateCtor(TITLE_RES, "LanConnectionsViewState ctor") }

private val restrictionGuard = findMethodDirect { findRestrictionGuard("getLanConnections") }

internal val UnlockLanConnections = patch(
    name = "Unlock LAN connections",
    description = "Unlocks LAN connections on free plans.",
) {
    unlockUserSetting(
        ::viewStateConstructor.constructor,
        getResourceId(ResourceType.STRING, TITLE_RES),
        ::restrictionGuard.method,
    )
}
