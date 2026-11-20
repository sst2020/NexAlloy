package io.github.nexalloy.hoodles.morphe.protonvpn.shared

import io.github.nexalloy.*
import io.github.nexalloy.morphe.*
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

// Anchors ported from hxreborn/morphe-patches protonvpn misc/anchors + restrictions.
// Note: hxreborn targets a newer ProtonVPN where entities live under
// com.protonvpn.android.* (older versions use me.proton.vpn.*).

internal const val SERVER_CLASS = "com.protonvpn.android.servers.Server"
internal const val SERVER_MANAGER_CLASS = "com.protonvpn.android.utils.ServerManager"
internal const val PROTOCOL_SELECTION_CLASS = "com.protonvpn.android.vpn.ProtocolSelection"
internal const val LOCAL_USER_SETTINGS_CLASS = "com.protonvpn.android.settings.data.LocalUserSettings"
internal const val UNIT_CLASS = "kotlin.Unit"

internal fun <T> List<T>.only(what: String = "method") =
    singleOrNull() ?: error("Expected exactly one $what, found $size")

internal fun <T> List<T>.firstOf(what: String = "method") =
    firstOrNull() ?: error("Expected at least one $what")

internal fun MethodData.isStatic() = Modifier.isStatic(modifiers)

internal fun MethodMatcher.dataClassToString(prefix: String) {
    name("toString")
    paramCount(0)
    returnType("java.lang.String")
    usingStrings(listOf(prefix), StringMatchType.StartsWith)
}

/** Class whose data class toString starts with [prefix]. */
internal fun DexKitBridge.dataClass(prefix: String): ClassData =
    findMethod { matcher { dataClassToString(prefix) } }
        .map { it.declaredClass!! }
        .distinctBy { it.name }
        .only("data class '$prefix'")

/** VpnUser type, resolved via the PartialVpnUserInt data class toString. */
internal val DexKitBridge.vpnUserClassName: String
    get() = dataClass("PartialVpnUserInt(").name

/** VpnUser.isFreeUser(): instance boolean reading a Number.intValue() field. */
internal fun DexKitBridge.findIsFreeUser() = findMethod {
    matcher {
        declaredClass(vpnUserClassName)
        paramCount(0)
        returnType("boolean")
        addInvoke("Ljava/lang/Number;->intValue()I")
    }
}.filter { !it.isStatic() }.only("VpnUser.isFreeUser")

/**
 * Server.isFree-like property getter: non-static boolean method whose body is
 * `iget` + `if-nez` (upstream `freeServerMethod`).
 */
internal fun DexKitBridge.findIsFreeServer() = findMethod {
    matcher {
        declaredClass(SERVER_CLASS)
        paramCount(0)
        returnType("boolean")
    }
}.filter { m ->
    !m.isStatic() && m.instructions.size >= 2 &&
        m.instructions[0].opcodeEnum == Opcode.IGET &&
        m.instructions[1].opcodeEnum == Opcode.IF_NEZ
}.only("Server.isFree")

/**
 * Methods containing a call to [callee] immediately followed by a move-result —
 * the runtime equivalent of upstream `freeUserCheckFingerprint` /
 * `freeServerCheckFingerprint`.
 */
internal fun DexKitBridge.findMethodsCalling(
    callee: MethodData,
    extraFilter: (MethodData) -> Boolean = { true },
) = findMethod {
    matcher { addInvoke(callee.descriptor) }
}.filter { m ->
    val index = m.instructions.indexOfFirst { it.methodRef?.descriptor == callee.descriptor }
    index >= 0 &&
        m.instructions.getOrNull(index + 1)?.opcodeEnum?.name?.startsWith("MOVE_RESULT") == true &&
        extraFilter(m)
}

/** Convenience for the common case: invokeSuspend lambdas calling [callee]. */
internal fun DexKitBridge.findSuspendMethodsCalling(
    callee: MethodData,
    extraFilter: (MethodData) -> Boolean = { true },
) = findMethodsCalling(callee) { m -> m.name == "invokeSuspend" && extraFilter(m) }

/**
 * Guard method that resets a user setting when the feature is restricted
 * (upstream `restrictionGuardFingerprint`): returns LocalUserSettings, takes
 * (LocalUserSettings, ..., VpnUser), calls [getter] on LocalUserSettings, and
 * branches on `if-eqz`.
 */
internal fun DexKitBridge.findRestrictionGuard(getter: String) = findMethod {
    matcher { returnType(LOCAL_USER_SETTINGS_CLASS) }
}.filter { m ->
    m.paramTypeNames.firstOrNull() == LOCAL_USER_SETTINGS_CLASS &&
        m.paramTypeNames.any { it == vpnUserClassName } &&
        m.invokes.any { it.name == getter && it.className == LOCAL_USER_SETTINGS_CLASS } &&
        m.instructions.any { it.opcodeEnum == Opcode.IF_EQZ }
}.only("restrictionGuard($getter)")

/**
 * Constructor of a class whose body reads the R.string field [resFieldName]
 * (upstream `resourceField` filter used by the ViewState fingerprints).
 */
internal fun DexKitBridge.findViewStateCtor(resFieldName: String, what: String) = findMethod {
    matcher { name("<init>") }
}.filter { m ->
    m.instructions.any {
        it.opcodeEnum == Opcode.SGET && it.fieldRef?.name == resFieldName
    }
}.only(what)

/**
 * Runtime port of upstream `unlockUserSetting`: the ViewState constructor gets
 * a `restricted` boolean right before the title string resource; force it off
 * and make the restriction guard keep the user setting by returning its input.
 */
internal fun unlockUserSetting(
    viewStateConstructor: Constructor<*>,
    titleResId: Int,
    restrictionGuard: Method,
) {
    viewStateConstructor.hookMethod {
        before { param ->
            val args = param.args
            for (i in 1 until args.size) {
                if (args[i] is Int && args[i] == titleResId && args[i - 1] is Boolean) {
                    args[i - 1] = false
                    return@before
                }
            }
        }
    }
    restrictionGuard.hookMethod {
        before { param -> param.result = param.args[0] }
    }
}

/**
 * Runtime port of upstream `keepUserSetting`: the restriction guard returns
 * LocalUserSettings possibly with the setting reset; always keep the input.
 */
internal fun keepUserSetting(restrictionGuard: Method) {
    restrictionGuard.hookMethod {
        before { param -> param.result = param.args[0] }
    }
}

/**
 * Runtime port of `clearFreeUserCheck`: force [callee] to return [value] while
 * [outer] executes.
 */
internal fun clearCheckIn(outer: Method, callee: Method, value: Boolean) {
    outer.hookMethod(scopedHook(callee) {
        after { param -> param.result = value }
    })
}

internal fun clearCheckIn(outers: List<Method>, callee: Method, value: Boolean) {
    outers.forEach { clearCheckIn(it, callee, value) }
}

/**
 * Runtime port of `filterConstructorProperty`: rewrite the constructor argument
 * assigned to the backing field of [property] in [dataClass].
 */
internal fun filterConstructorProperty(
    dataClass: Class<*>,
    field: java.lang.reflect.Field,
    transform: (Any?) -> Any?,
) {
    val fields = dataClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
    val index = fields.indexOfFirst { it.name == field.name && it.type == field.type }
    require(index >= 0) { "No ctor slot for ${field.name} in ${dataClass.name}" }
    val ctor = dataClass.declaredConstructors.single { it.parameterCount == fields.size }
    ctor.hookMethod {
        before { param ->
            if (index < param.args.size) param.args[index] = transform(param.args[index])
        }
    }
}

/** Runtime port of `clearConstructorProperty` for boolean properties. */
internal fun clearConstructorProperty(dataClass: Class<*>, field: java.lang.reflect.Field) =
    filterConstructorProperty(dataClass, field) { false }

/**
 * Read the backing field of a data class property (upstream `findPropertyField`):
 * property labels appear in the toString method as `"name="` strings in
 * property order; fields are read in the same order.
 */
internal fun findPropertyField(toString: MethodData, property: String): FieldData {
    val labels = toString.usingStrings.filter { it.endsWith("=") }.map { it.dropLast(1) }
    val fields = toString.usingFields
        .filter { it.field.declaredClassName == toString.className }
        .map { it.field }
        .distinct()
    require(labels.size == fields.size) {
        "toString of ${toString.className} has ${labels.size} labels but ${fields.size} fields"
    }
    val index = labels.indexOf(property)
    require(index >= 0) { "${toString.className} has no property '$property'" }
    return fields[index]
}

/** FieldData for [property] of the data class whose toString starts with [prefix]. */
internal fun DexKitBridge.propertyFieldData(toStringPrefix: String, property: String): FieldData =
    findPropertyField(
        findMethod { matcher { dataClassToString(toStringPrefix) } }.only("$toStringPrefix toString"),
        property,
    )

/** Getter method reading the backing [field] — upstream `propertyGetter`. */
internal fun DexKitBridge.findPropertyGetter(ownerClass: String, field: FieldData, returnType: String) =
    findMethod {
        matcher {
            declaredClass(ownerClass)
            paramCount(0)
            returnType(returnType)
        }
    }.filter { m ->
        !m.isStatic() && m.instructions.any { it.fieldRef?.descriptor == field.descriptor }
    }.only("getter for ${field.name}")

/** Whether [this] is an invokeSuspend-style lambda receiving [type]. */
internal fun MethodData.receivesType(type: String) =
    paramTypeNames.firstOrNull() == type ||
        instructions.firstOrNull()?.let {
            it.opcodeEnum == Opcode.CHECK_CAST &&
                it.classRef?.descriptor == "L${type.replace('.', '/')};"
        } == true

// VpnCountry anchors shared by the profiles and connection-preferences ports.

internal val vpnCountryServerListField = findFieldDirect {
    propertyFieldData("VpnCountry(flag=", "serverList")
}

internal val vpnCountryServerListGetter = findMethodDirect {
    val country = dataClass("VpnCountry(flag=").name
    findPropertyGetter(country, propertyFieldData("VpnCountry(flag=", "serverList"), "java.util.List")
}

internal val vpnCountryFlagField = findFieldDirect {
    propertyFieldData("VpnCountry(flag=", "flag")
}

internal val vpnCountryConstructor = findMethodDirect {
    val country = dataClass("VpnCountry(flag=").name
    findMethod {
        matcher {
            name("<init>")
            declaredClass(country)
        }
    }.filter { it.paramTypeNames == listOf("java.lang.String", "java.util.List") }
        .only("VpnCountry(String, List) ctor")
}
