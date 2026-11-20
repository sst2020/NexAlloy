package io.github.nexalloy.hoodles.morphe.protonvpn.customdns

import io.github.nexalloy.*
import io.github.nexalloy.hoodles.morphe.protonvpn.shared.*
import io.github.nexalloy.morphe.*
import io.github.nexalloy.morphe.ResourceType
import io.github.nexalloy.morphe.getResourceId

// Port of hxreborn unlockCustomDnsPatch.

private const val TITLE_RES = "settings_custom_dns_title"

private val viewStateConstructor = findMethodDirect { findViewStateCtor(TITLE_RES, "CustomDnsViewState ctor") }

private val restrictionGuard = findMethodDirect { findRestrictionGuard("getCustomDns") }

internal val UnlockCustomDns = patch(
    name = "Unlock custom DNS",
    description = "Unlocks custom DNS on free plans.",
) {
    unlockUserSetting(
        ::viewStateConstructor.constructor,
        getResourceId(ResourceType.STRING, TITLE_RES),
        ::restrictionGuard.method,
    )
}
