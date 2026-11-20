package io.github.nexalloy.hoodles.morphe.protonvpn.netshield

import io.github.nexalloy.*
import io.github.nexalloy.hoodles.morphe.protonvpn.shared.*
import io.github.nexalloy.morphe.*
import org.luckypray.dexkit.query.enums.StringMatchType

// Port of hxreborn unlockNetShieldPatch.

private const val RESET_LOG_PREFIX = "reset default profile: "

// Enum class whose <clinit> creates the AVAILABLE / UPGRADE_VPN_PLUS constants.
private val netShieldAvailabilityEnumClinit = findMethodDirect {
    findMethod {
        matcher {
            name("<clinit>")
            usingStrings(listOf("AVAILABLE", "UPGRADE_VPN_PLUS"), StringMatchType.Equals)
        }
    }.only("NetShieldAvailability clinit")
}

// Methods gating NetShield availability on isFreeUser, reading an enum constant.
private val netShieldAvailabilityChecks = findMethodListDirect {
    val isFreeUser = findIsFreeUser()
    val enumClass = findMethod {
        matcher {
            name("<clinit>")
            usingStrings(listOf("AVAILABLE", "UPGRADE_VPN_PLUS"), StringMatchType.Equals)
        }
    }.only("NetShieldAvailability clinit").className
    findMethodsCalling(isFreeUser) { m ->
        m.instructions.any {
            it.opcodeEnum == Opcode.SGET_OBJECT && it.fieldRef?.declaredClassName == enumClass
        }
    }
}

// The "reset default profile" log path reads a LocalUserSettings NetShield getter.
private val netShieldResetChecks = findMethodListDirect {
    val isFreeUser = findIsFreeUser()
    findMethodsCalling(isFreeUser) { m ->
        m.usingStrings.any { it.startsWith(RESET_LOG_PREFIX) } &&
            m.invokes.any {
                it.className == LOCAL_USER_SETTINGS_CLASS && "NetShield" in it.name &&
                    it.paramCount == 0 && it.returnTypeName != "void"
            }
    }
}

internal val UnlockNetShield = patch(
    name = "Unlock NetShield",
    description = "Unlocks NetShield ad and tracker blocking on free plans.",
) {
    val isFreeUser = ::vpnUserIsFreeUser.method
    clearCheckIn(::netShieldAvailabilityChecks.dexMethodList.map { it.toMethod() }, isFreeUser, false)
    clearCheckIn(::netShieldResetChecks.dexMethodList.map { it.toMethod() }, isFreeUser, false)
}
