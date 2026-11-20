package io.github.nexalloy.hoodles.morphe.protonvpn.profiles

import io.github.nexalloy.*
import io.github.nexalloy.hoodles.morphe.protonvpn.shared.*
import io.github.nexalloy.morphe.*

// Port of hxreborn unlockProfilesPatch.

// Method deciding profile availability; gated on isFreeUser, returns Object,
// takes (..., VpnUser, ..., Long, ..., List, ...).
private val profileAvailabilityCheck = findMethodDirect {
    val isFreeUser = findIsFreeUser()
    val vpnUser = vpnUserClassName
    findMethodsCalling(isFreeUser) { m ->
        m.returnTypeName == "java.lang.Object" &&
            vpnUser in m.paramTypeNames &&
            "java.lang.Long" in m.paramTypeNames &&
            "java.util.List" in m.paramTypeNames
    }.only("profile availability check")
}

// ServerManager method returning vpnCountries; reads ServerLists.vpnCountries
// and calls Collator.getInstance.
private val vpnCountries = findMethodDirect {
    val vpnCountriesField = propertyFieldData("ServerLists(allServers=", "vpnCountries")
    findMethod {
        matcher {
            declaredClass(SERVER_MANAGER_CLASS)
            paramCount(0)
            returnType("java.util.List")
            addInvoke("Ljava/text/Collator;->getInstance()Ljava/text/Collator;")
        }
    }.filter { m ->
        m.instructions.any { it.fieldRef?.descriptor == vpnCountriesField.descriptor }
    }.only("vpnCountries")
}

// Profile server picker filter: the isFreeServer call result is XOR'ed —
// invert it for free accounts so free servers are shown instead of paid ones.
private val profileServerFilter = findMethodDirect {
    val isFreeServer = findIsFreeServer()
    findMethodsCalling(isFreeServer) { m ->
        val index = m.instructions.indexOfFirst { it.methodRef?.descriptor == isFreeServer.descriptor }
        m.instructions.getOrNull(index + 2)?.opcodeEnum?.name?.startsWith("XOR_INT") == true
    }.only("profile server filter")
}

// Cities/states list per country: instance method with >= 3 params, first a
// String, no CityStateId param, calling VpnCountry.getServerList.
private val profileCitiesOrStates = findMethodListDirect {
    val country = dataClass("VpnCountry(flag=").name
    val cityStateId = dataClass("CityStateId(name=").name
    val getter = findPropertyGetter(
        country,
        propertyFieldData("VpnCountry(flag=", "serverList"),
        "java.util.List",
    )
    findMethod {
        matcher { addInvoke(getter.descriptor) }
    }.filter { m ->
        !m.isStatic() && m.paramTypeNames.size >= 3 &&
            m.paramTypeNames.first() == "java.lang.String" &&
            cityStateId !in m.paramTypeNames
    }
}

private val profileAvailabilityField = findFieldDirect {
    propertyFieldData("ProfileViewItem(profile=", "availability")
}

private val profilesListClass = findClassDirect {
    dataClass("ProfilesList(profiles=")
}

private val profilesListProfilesField = findFieldDirect {
    propertyFieldData("ProfilesList(profiles=", "profiles")
}

private val standardProfileTypeClass = findClassDirect {
    dataClass("Standard(availableTypes=")
}
private val standardAvailableTypesField = findFieldDirect {
    propertyFieldData("Standard(availableTypes=", "availableTypes")
}
private val secureCoreProfileTypeClass = findClassDirect {
    dataClass("SecureCore(availableTypes=")
}
private val secureCoreAvailableTypesField = findFieldDirect {
    propertyFieldData("SecureCore(availableTypes=", "availableTypes")
}
private val p2pProfileTypeClass = findClassDirect {
    dataClass("P2P(availableTypes=")
}
private val p2pAvailableTypesField = findFieldDirect {
    propertyFieldData("P2P(availableTypes=", "availableTypes")
}
private val gatewayProfileTypeClass = findClassDirect {
    dataClass("Gateway(availableTypes=")
}
private val gatewayAvailableTypesField = findFieldDirect {
    propertyFieldData("Gateway(availableTypes=", "availableTypes")
}

@Suppress("UNCHECKED_CAST")
internal val UnlockProfiles = patch(
    name = "Unlock profiles",
    description = "Unlocks profiles on free plans and limits them to free locations. " +
        "Profiles for other locations are hidden.",
) {
    dependsOn(FreeAccountTracking)

    FreeServerLocations.serverIsFree = ::isFreeServerGetter.method
    FreeServerLocations.countryServerList = ::vpnCountryServerListGetter.method
    FreeServerLocations.profileAvailability = ::profileAvailabilityField.field

    val isFreeUser = ::vpnUserIsFreeUser.method
    clearCheckIn(::profileAvailabilityCheck.method, isFreeUser, false)

    // Countries visible to free accounts keep only countries with free servers.
    ::vpnCountries.method.hookMethod {
        after { param ->
            param.result?.let { param.result = FreeServerLocations.countriesForAccount(it as List<*>) }
        }
    }

    ::profileServerFilter.method.invertFreeServerCheck(::isFreeServerGetter.method)

    // Server list shown for a picked city/state: keep free servers only.
    val serverListGetter = ::vpnCountryServerListGetter.method
    ::profileCitiesOrStates.dexMethodList.forEach { outer ->
        outer.hookMethod(scopedHook(serverListGetter) {
            after { param ->
                param.result?.let { param.result = FreeServerLocations.serversForAccount(it as List<*>) }
            }
        })
    }

    filterConstructorProperty(::profilesListClass.clazz, ::profilesListProfilesField.field) {
        FreeServerLocations.profilesForAccount(it as List<*>)
    }
    listOf(
        ::standardProfileTypeClass to ::standardAvailableTypesField,
        ::secureCoreProfileTypeClass to ::secureCoreAvailableTypesField,
        ::p2pProfileTypeClass to ::p2pAvailableTypesField,
        ::gatewayProfileTypeClass to ::gatewayAvailableTypesField,
    ).forEach { (cls, field) ->
        filterConstructorProperty(cls.clazz, field.field) {
            FreeServerLocations.profileTypesForAccount(it as List<*>)
        }
    }
}
