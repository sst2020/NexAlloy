package io.github.nexalloy.hoodles.morphe.protonvpn.splittunneling

import io.github.nexalloy.*
import io.github.nexalloy.hoodles.morphe.protonvpn.shared.*
import io.github.nexalloy.morphe.*
import io.github.nexalloy.morphe.ResourceType
import io.github.nexalloy.morphe.getResourceId

// Port of hxreborn unlockSplitTunnelingPatch.

private const val TITLE_RES = "settings_split_tunneling_title"

private val viewStateConstructor = findMethodDirect { findViewStateCtor(TITLE_RES, "SplitTunnelingViewState ctor") }

private val restrictionGuard = findMethodDirect { findRestrictionGuard("getSplitTunneling") }

internal val UnlockSplitTunneling = patch(
    name = "Unlock split tunneling",
    description = "Unlocks split tunneling on free plans.",
) {
    unlockUserSetting(
        ::viewStateConstructor.constructor,
        getResourceId(ResourceType.STRING, TITLE_RES),
        ::restrictionGuard.method,
    )
}
