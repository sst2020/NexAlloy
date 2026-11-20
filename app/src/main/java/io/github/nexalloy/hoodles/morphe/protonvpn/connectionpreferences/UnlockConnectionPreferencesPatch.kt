package io.github.nexalloy.hoodles.morphe.protonvpn.connectionpreferences

import io.github.nexalloy.*
import io.github.nexalloy.hoodles.morphe.protonvpn.shared.*
import io.github.nexalloy.morphe.*
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

// Port of hxreborn unlockConnectionPreferencesPatch. Two bytecode-level pieces
// cannot be ported faithfully: `alwaysTakeLastIfEqz` (forcing a branch) — the
// runtime port forces the isFreeUser call in the same methods instead — and the
// `keepUserSetting` no-op, replaced by returning the guard's input unchanged.

private const val SERVER_DESCRIPTOR = "Lcom/protonvpn/android/servers/Server;"

private val connectionPreferencesClass = findClassDirect {
    dataClass("ConnectionPreferencesState(isFeatureDiscovered=")
}

private val connectionPreferencesIsFreeUserField = findFieldDirect {
    propertyFieldData("ConnectionPreferencesState(isFeatureDiscovered=", "isFreeUser")
}

private val defaultConnectionRestriction = findMethodDirect {
    findRestrictionGuard("getDefaultProfileId")
}

// invokeSuspend recording recents: isFreeUser + if-nez + SGET of a Unit field.
private val recentsRecording = findMethodDirect {
    val isFreeUser = findIsFreeUser()
    findSuspendMethodsCalling(isFreeUser) { m ->
        val index = m.instructions.indexOfFirst { it.methodRef?.descriptor == isFreeUser.descriptor }
        m.instructions.getOrNull(index + 2)?.opcodeEnum == Opcode.IF_NEZ &&
            m.instructions.any {
                it.opcodeEnum == Opcode.SGET_OBJECT && it.fieldRef?.className == UNIT_CLASS
            }
    }.only("recents recording")
}

// Recents list flow: the free-user check lives in a lambda class it instantiates.
private val recentsListLambda = findMethodListDirect {
    val isFreeUser = findIsFreeUser()
    val owner = findMethod {
        matcher {
            name("<init>")
            usingStrings(
                listOf("changeServerManager", "observeConnectionFeedbackViewState"),
                StringMatchType.Equals,
            )
        }
    }.only("RecentsListViewStateFlow ctor").className
    val instantiated = findMethod {
        matcher { declaredClass(owner) }
    }.flatMap { m ->
        m.instructions.mapNotNull {
            if (it.opcodeEnum == Opcode.NEW_INSTANCE) it.classRef?.descriptor else null
        }
    }.toSet()
    instantiated.flatMap { desc ->
        findMethod {
            matcher {
                declaredClass(desc.removePrefix("L").removeSuffix(";").replace('/', '.'))
                name("invokeSuspend")
                addInvoke(isFreeUser.descriptor)
            }
        }
    }
}

// invokeSuspend calling isFreeUser then a 7-arg static on the default-
// connection view-state flow owner.
private val defaultConnectionRecents = findMethodListDirect {
    val isFreeUser = findIsFreeUser()
    val vpnUser = vpnUserClassName
    val owner = findMethod {
        matcher {
            name("<init>")
            usingStrings(
                listOf(
                    "effectiveCurrentUserSettings",
                    "getConnectIntentViewState",
                    "observeDefaultConnection",
                ),
                StringMatchType.Equals,
            )
        }
    }.only("DefaultConnectionViewStateFlow ctor").className
    findSuspendMethodsCalling(isFreeUser) { m ->
        m.invokes.any { it.className == owner && it.paramCount == 7 }
    }
}

// GetQuickConnectIntent use case: invokeSuspend with a free-user check.
private val quickConnectIntent = findMethodListDirect {
    val isFreeUser = findIsFreeUser()
    val owner = findMethod {
        matcher {
            name("<init>")
            paramCount(7)
            usingStrings(
                listOf("getDefaultConnectIntent", "observeDefaultConnection"),
                StringMatchType.Equals,
            )
        }
    }.only("GetQuickConnectIntent ctor").className
    findMethodsCalling(isFreeUser) { m ->
        m.className == owner && m.returnTypeName == "java.lang.Object" && m.paramCount == 1
    }
}

private val addExcludedLocationCheck = findMethodDirect {
    val isFreeUser = findIsFreeUser()
    findMethodsCalling(isFreeUser) { m ->
        m.usingStrings.contains("Cannot add excluded location: free VPN user is not allowed")
    }.only("add excluded location check")
}

private val removeExcludedLocationCheck = findMethodDirect {
    val isFreeUser = findIsFreeUser()
    findMethodsCalling(isFreeUser) { m ->
        m.usingStrings.contains("Cannot remove excluded location: free VPN user is not allowed")
    }.only("remove excluded location check")
}

private val excludedLocationsClass = findClassDirect {
    dataClass("ExcludedLocations(allLocations=")
}

private val excludedLocationsConstructor = findMethodDirect {
    val excluded = dataClass("ExcludedLocations(allLocations=").name
    findMethod {
        matcher {
            name("<init>")
            declaredClass(excluded)
            paramCount(1)
        }
    }.filter { it.paramTypeNames == listOf("java.util.List") }
        .only("ExcludedLocations ctor")
}

private val excludedLocationsFlow = findMethodDirect {
    val isFreeUser = findIsFreeUser()
    val excluded = dataClass("ExcludedLocations(allLocations=").name
    findSuspendMethodsCalling(isFreeUser) { m ->
        m.instructions.any {
            it.opcodeEnum == Opcode.SGET_OBJECT && it.fieldRef?.className == excluded
        } || m.invokes.any { it.returnTypeName == excluded }
    }.only("excluded locations flow")
}

// public static final boolean serverIsExcluded(Server, ExcludedLocations).
private val serverIsExcluded = findMethodDirect {
    val excluded = dataClass("ExcludedLocations(allLocations=").name
    findMethod {
        matcher {
            paramCount(2)
            returnType("boolean")
            addInvoke("$SERVER_DESCRIPTOR->getExitCountry()Ljava/lang/String;")
        }
    }.filter { m ->
        m.isStatic() && m.paramTypeNames == listOf(SERVER_CLASS, excluded)
    }.only("serverIsExcluded")
}

// Inner (boolean) -> List<country> call inside ServerManager used by randomServer.
private val exitCountriesGetter = findMethodDirect {
    findMethod {
        matcher {
            declaredClass(SERVER_MANAGER_CLASS)
            paramCount(1)
            returnType("java.util.List")
        }
    }.filter { it.paramTypeNames.firstOrNull() == "boolean" }
        .only("exit countries getter")
}

// ServerManager.randomServer(VpnUser, ProtocolSelection, List): Server.
private val randomServer = findMethodDirect {
    val vpnUser = vpnUserClassName
    val getter = findMethod {
        matcher {
            declaredClass(SERVER_MANAGER_CLASS)
            paramCount(1)
            returnType("java.util.List")
        }
    }.filter { it.paramTypeNames.firstOrNull() == "boolean" }
        .only("exit countries getter")
    findMethod {
        matcher {
            declaredClass(SERVER_MANAGER_CLASS)
            returnType(SERVER_CLASS)
            addInvoke(getter.descriptor)
        }
    }.filter { m ->
        m.paramTypeNames.size == 3 &&
            m.paramTypeNames[0] == vpnUser &&
            m.paramTypeNames[1] == PROTOCOL_SELECTION_CLASS &&
            m.paramTypeNames[2] == "java.util.List" &&
            m.invokes.any { it.name == "hasAccessibleOnlineServer" }
    }.only("randomServer")
}

// Inner ServerManager call used while building a connect intent:
// (L, VpnUser, ProtocolSelection, List, ExcludedLocations) -> Server.
private val bestServerInner = findMethodDirect {
    val vpnUser = vpnUserClassName
    val excluded = dataClass("ExcludedLocations(allLocations=").name
    findMethod {
        matcher {
            declaredClass(SERVER_MANAGER_CLASS)
            paramCount(5)
            returnType(SERVER_CLASS)
        }
    }.filter { m ->
        m.paramTypeNames[1] == vpnUser &&
            m.paramTypeNames[2] == PROTOCOL_SELECTION_CLASS &&
            m.paramTypeNames[4] == excluded
    }.only("bestServer inner")
}

// Caller of the inner best-server lookup; takes the same VpnUser and
// ExcludedLocations.
private val bestServerForConnectIntent = findMethodDirect {
    val vpnUser = vpnUserClassName
    val excluded = dataClass("ExcludedLocations(allLocations=").name
    val inner = findMethod {
        matcher {
            declaredClass(SERVER_MANAGER_CLASS)
            paramCount(5)
            returnType(SERVER_CLASS)
        }
    }.filter { m ->
        m.paramTypeNames[1] == vpnUser &&
            m.paramTypeNames[2] == PROTOCOL_SELECTION_CLASS &&
            m.paramTypeNames[4] == excluded
    }.only("bestServer inner")
    findMethod {
        matcher { addInvoke(inner.descriptor) }
    }.filter { m ->
        m.paramTypeNames.size == 5 &&
            m.paramTypeNames[1] == vpnUser &&
            m.paramTypeNames[2] == PROTOCOL_SELECTION_CLASS &&
            m.paramTypeNames[3] == excluded
    }.only("bestServerForConnectIntent")
}

// Connection card label paths: either an invokeSuspend calling isFreeUser whose
// result feeds a VpnConnectionCardViewState call, or an inlined variant that
// instantiates the view state directly.
private val connectionCardLabelChecks = findMethodListDirect {
    val isFreeUser = findIsFreeUser()
    val cardDescriptor = "L${dataClass("VpnConnectionCardViewState(cardLabel=").name.replace('.', '/')};"
    findSuspendMethodsCalling(isFreeUser) { m ->
        val index = m.instructions.indexOfFirst { it.methodRef?.descriptor == isFreeUser.descriptor }
        m.instructions.drop(index).take(4).any {
            it.methodRef?.descriptor?.contains(cardDescriptor) == true
        }
    }
}

private val inlinedConnectionCard = findMethodListDirect {
    val isFreeUser = findIsFreeUser()
    val cardDescriptor = "L${dataClass("VpnConnectionCardViewState(cardLabel=").name.replace('.', '/')};"
    findSuspendMethodsCalling(isFreeUser) { m ->
        m.instructions.any {
            it.opcodeEnum == Opcode.NEW_INSTANCE && it.classRef?.descriptor == cardDescriptor
        }
    }
}

private val defaultConnectionSettingChecks = findMethodListDirect {
    val isFreeUser = findIsFreeUser()
    val stateDescriptor = "L${dataClass("DefaultConnectionSettingState(iconRes=").name.replace('.', '/')};"
    findSuspendMethodsCalling(isFreeUser) { m ->
        m.invokes.any { it.paramCount == 1 && it.returnTypeName == "java.lang.Long" } &&
            m.instructions.any {
                it.opcodeEnum == Opcode.NEW_INSTANCE && it.classRef?.descriptor == stateDescriptor
            }
    }
}

private val inlinedDefaultConnectionSetting = findMethodListDirect {
    val isFreeUser = findIsFreeUser()
    val recentDescriptor = "L${dataClass("Recent(recentId=").name.replace('.', '/')};"
    val stateDescriptor = "L${dataClass("DefaultConnectionSettingState(iconRes=").name.replace('.', '/')};"
    findSuspendMethodsCalling(isFreeUser) { m ->
        m.instructions.any {
            it.opcodeEnum == Opcode.INSTANCE_OF && it.classRef?.descriptor == recentDescriptor
        } && m.instructions.any {
            it.opcodeEnum == Opcode.NEW_INSTANCE && it.classRef?.descriptor == stateDescriptor
        }
    }
}

/** Kotlin port of the ExcludedLocations extension helpers. */
internal object ExcludedLocationsFilter {
    @Volatile
    var latest: Any? = null

    lateinit var isExcludedMethod: Method
    lateinit var countryConstructor: Constructor<*>
    lateinit var flagField: Field
    lateinit var serverListGetter: Method
    lateinit var isFreeUser: Method

    fun countriesWithoutExcludedServers(countries: List<*>): List<*> {
        val excluded = latest ?: return countries
        return countries.filterNotNull().mapNotNull { country ->
            val servers = serverListGetter.invoke(country) as List<*>
            val allowed = servers.filterNotNull().filter { !isExcluded(it, excluded) }
            when {
                allowed.size == servers.size -> country
                allowed.isEmpty() -> null
                else -> countryConstructor.newInstance(flagField.get(country), allowed)
            }
        }
    }

    private fun isExcluded(server: Any, excluded: Any) =
        isExcludedMethod.invoke(null, server, excluded) as Boolean

    fun serverUnlessExcludedForFreeUser(server: Any?, vpnUser: Any?, excluded: Any?): Any? {
        if (server == null || vpnUser == null || excluded == null) return server
        if (isFreeUser.invoke(vpnUser) != true) return server
        return if (isExcludedMethod.invoke(null, server, excluded) as Boolean) null else server
    }
}

@Suppress("UNCHECKED_CAST")
internal val UnlockConnectionPreferences = patch(
    name = "Unlock connection preferences",
    description = "Unlocks default connection, recents and excluded locations on free plans.",
) {
    val isFreeUser = ::vpnUserIsFreeUser.method

    clearConstructorProperty(
        ::connectionPreferencesClass.clazz,
        ::connectionPreferencesIsFreeUserField.field,
    )
    keepUserSetting(::defaultConnectionRestriction.method)
    clearCheckIn(::recentsRecording.method, isFreeUser, false)
    clearCheckIn(::recentsListLambda.dexMethodList.map { it.toMethod() }, isFreeUser, false)
    clearCheckIn(::defaultConnectionRecents.dexMethodList.map { it.toMethod() }, isFreeUser, false)
    clearCheckIn(::quickConnectIntent.dexMethodList.map { it.toMethod() }, isFreeUser, false)
    clearCheckIn(::addExcludedLocationCheck.method, isFreeUser, false)
    clearCheckIn(::removeExcludedLocationCheck.method, isFreeUser, false)
    clearCheckIn(::excludedLocationsFlow.method, isFreeUser, false)

    // Track the latest ExcludedLocations instance (upstream onExcludedLocationsCreated).
    ::excludedLocationsConstructor.constructor.hookMethod {
        after { param -> ExcludedLocationsFilter.latest = param.thisObject }
    }
    ExcludedLocationsFilter.isExcludedMethod = ::serverIsExcluded.method
    ExcludedLocationsFilter.countryConstructor = ::vpnCountryConstructor.constructor
    ExcludedLocationsFilter.flagField = ::vpnCountryFlagField.field
    ExcludedLocationsFilter.serverListGetter = ::vpnCountryServerListGetter.method
    ExcludedLocationsFilter.isFreeUser = isFreeUser

    // Random server: strip countries whose exit servers are all excluded.
    ::randomServer.method.hookMethod(scopedHook(::exitCountriesGetter.method) {
        after { param ->
            param.result?.let {
                param.result = ExcludedLocationsFilter.countriesWithoutExcludedServers(it as List<*>)
            }
        }
    })

    // Connect intent: null out the best server when it is excluded for a free user.
    ::bestServerForConnectIntent.method.hookMethod(scopedHook(::bestServerInner.method) {
        after { param ->
            param.result = ExcludedLocationsFilter.serverUnlessExcludedForFreeUser(
                param.result, param.args.getOrNull(1), param.args.getOrNull(4),
            )
        }
    })

    ::connectionCardLabelChecks.dexMethodList
        .ifEmpty { ::inlinedConnectionCard.dexMethodList }
        .forEach { clearCheckIn(it.toMethod(), isFreeUser, false) }
    ::defaultConnectionSettingChecks.dexMethodList
        .ifEmpty { ::inlinedDefaultConnectionSetting.dexMethodList }
        .forEach { clearCheckIn(it.toMethod(), isFreeUser, false) }
}
