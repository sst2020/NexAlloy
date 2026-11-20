package io.github.nexalloy.hoodles.morphe.protonvpn.shared

import io.github.nexalloy.*
import io.github.nexalloy.morphe.*
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

// Port of hxreborn's FreeAccount extension + freeAccountStatePatch:
// tracks whether the signed-in account is on the free plan so feature unlocks
// can downgrade server lists to free locations instead of leaking paid ones.

internal object FreeAccountState {
    @Volatile
    var signedIn = false
        internal set
}

internal val vpnUserIsFreeUser = findMethodDirect { findIsFreeUser() }

internal val isFreeServerGetter = findMethodDirect { findIsFreeServer() }

internal val userInfoClass = findClassDirect {
    dataClass("PartialJointUserInt(user=")
}

internal val vpnUserClass = findClassDirect {
    dataClass("PartialVpnUserInt(")
}

// invokeSuspend(userInfo, continuation) pushing the value into a StateFlow.
private val userInfoUpdate = findMethodDirect {
    val userInfo = dataClass("PartialJointUserInt(user=").name
    findMethod {
        matcher {
            name("invokeSuspend")
            paramCount(2)
            returnType("java.lang.Object")
            addInvoke("Ljava/lang/Number;->longValue()J")
        }
    }.filter { m ->
        m.receivesType(userInfo) &&
            m.invokes.any { it.paramCount == 1 && it.returnTypeName == "void" }
    }.only("UserInfo update")
}

// StateFlow invalidation: emits a 0L-boxed sentinel.
private val userInfoInvalidate = findMethodDirect {
    findMethod {
        matcher {
            paramCount(0)
            returnType("void")
            usingNumbers(0)
            addInvoke("Ljava/lang/Number;->longValue()J")
            addInvoke("Ljava/lang/Long;->valueOf(J)Ljava/lang/Long;")
        }
    }.filter { m ->
        m.invokes.any { it.paramCount == 2 && it.returnTypeName == "boolean" }
    }.only("UserInfo invalidate")
}

internal val FreeAccountTracking = patch(
    name = "Track free account",
    description = "Tracks whether the signed-in account is on the free plan.",
    use = false,
) {
    val userInfoClass = ::userInfoClass.clazz
    val vpnUserClass = ::vpnUserClass.clazz
    val isFreeUser = ::vpnUserIsFreeUser.method
    val vpnUserField = userInfoClass.declaredFields.single {
        it.type == vpnUserClass && !Modifier.isStatic(it.modifiers)
    }
    vpnUserField.isAccessible = true

    ::userInfoUpdate.method.hookMethod {
        before { param ->
            val info = param.args.getOrNull(0)
            if (info != null && userInfoClass.isInstance(info)) {
                runCatching {
                    FreeAccountState.signedIn = isFreeUser.invoke(vpnUserField.get(info)) as Boolean
                }
            }
        }
    }
    ::userInfoInvalidate.method.hookMethod {
        before { FreeAccountState.signedIn = false }
    }
}

/**
 * Kotlin port of the FreeServerLocations extension. Member handles are real
 * reflect objects resolved by the owning patch instead of name stubs.
 */
internal object FreeServerLocations {
    lateinit var serverIsFree: Method
    lateinit var countryServerList: Method
    lateinit var profileAvailability: Field
    var serverGroupTier: Method? = null

    private const val FREE_TIER = 0
    private const val STANDARD_PROFILE_TYPE = "Standard"
    private const val UNAVAILABLE_PLAN = "UNAVAILABLE_PLAN"

    fun shouldExcludeServer(isFreeServer: Boolean) = FreeAccountState.signedIn != isFreeServer

    fun tierForAvailabilityCheck(item: Any, userTier: Int?): Int? {
        if (userTier != null && userTier == FREE_TIER && tierOf(item) == FREE_TIER) return FREE_TIER + 1
        return userTier
    }

    fun itemsVisibleToTier(items: List<*>, userTier: Int?): List<*> {
        if (userTier == null || userTier != FREE_TIER) return items
        return items.filterNotNull().filter { tierOf(it) == FREE_TIER }
    }

    @Suppress("UNCHECKED_CAST")
    fun resolveSelectedFilter(filter: Any?): Any? {
        if (!FreeAccountState.signedIn || filter == null) return filter
        return java.lang.Enum.valueOf(filter.javaClass as Class<out Enum<*>>, "All")
    }

    fun resolveFilterButtons(buttons: List<*>): List<*> =
        if (FreeAccountState.signedIn) emptyList<Any>() else buttons

    fun countriesForAccount(countries: List<*>): List<*> {
        if (!FreeAccountState.signedIn) return countries
        return countries.filterNotNull().filter(::hasFreeServer)
    }

    fun profileTypesForAccount(types: List<*>): List<*> {
        if (!FreeAccountState.signedIn) return types
        return types.filterIsInstance<Enum<*>>().filter { it.name == STANDARD_PROFILE_TYPE }
    }

    fun serversForAccount(servers: List<*>): List<*> {
        if (!FreeAccountState.signedIn) return servers
        return servers.filterNotNull().filter(::isFreeServer)
    }

    fun profilesForAccount(profiles: List<*>): List<*> {
        if (!FreeAccountState.signedIn) return profiles
        return profiles.filterNotNull().filter {
            (profileAvailability.get(it) as? Enum<*>)?.name != UNAVAILABLE_PLAN
        }
    }

    private fun isFreeServer(server: Any) = serverIsFree.invoke(server) as Boolean

    private fun hasFreeServer(country: Any): Boolean {
        val servers = countryServerList.invoke(country) as? List<*> ?: return false
        return servers.filterNotNull().any(::isFreeServer)
    }

    private fun tierOf(item: Any) = serverGroupTier!!.invoke(item) as Int
}

/**
 * Runtime port of `invertFreeServerCheckForFreeAccount`: pipe calls to the
 * isFreeServer getter inside this method through
 * [FreeServerLocations.shouldExcludeServer].
 */
internal fun Method.invertFreeServerCheck(inner: Method) {
    hookMethod(scopedHook(inner) {
        after { param ->
            param.result?.let { param.result = FreeServerLocations.shouldExcludeServer(it as Boolean) }
        }
    })
}
