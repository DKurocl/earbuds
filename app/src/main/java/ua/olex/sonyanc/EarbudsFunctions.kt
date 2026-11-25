package ua.olex.sonyanc

import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import androidx.appfunctions.AppFunctionStringValueConstraint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What Gemini can do with the earbuds ("Hey Google, turn on Ambient on my earbuds"), through
 * Android's App Functions. Each call is the same as a tap in the widget: a short connection
 * lease, the command, the earbuds' confirmation. The KDoc below is what the assistant reads.
 */
@RequiresApi(36) // App Functions arrived in Android 16; only the Pixel's Android 17 binds it.
@AppFunctionServiceEntryPoint(serviceName = "EarbudsFunctionService", appFunctionXmlFileName = "earbuds_functions")
abstract class EarbudsFunctions : AppFunctionService() {
    private val setupRequired = "Open Earbuds once to choose your WF-1000XM5."

    /**
     * Changes the noise control of the user's Sony WF-1000XM5 earbuds.
     *
     * @param mode "noise_cancelling" blocks outside sound, "ambient" lets it in (to hear people
     *   and traffic), "off" turns both off.
     * @return What the earbuds are set to now, as a short sentence for the user.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun setNoiseControl(
        @AppFunctionStringValueConstraint(enumValues = ["noise_cancelling", "ambient", "off"]) mode: String,
    ): String {
        val wanted = modeOf(mode)
        AmbientTimer.cancel(this) // a choice of one's own wins over "Ambient for a moment"
        return withLink {
            val base = Headphones.state.value.shownNc ?: SonyProtocol.NcState(wanted)
            val ok = withTimeoutOrNull(8500) {
                Headphones.request(base.copy(mode = wanted)).await() && confirmedMode(wanted)
            } ?: false
            if (ok) "${wanted.label} is on." else notReachable()
        } ?: setupRequired
    }

    /**
     * Turns on ambient sound for a few minutes, then returns the earbuds to the mode they were in
     * (noise cancelling if they were already in ambient). Useful for a quick conversation or an
     * announcement.
     *
     * @param minutes How long to stay in ambient sound, from 1 to 30 minutes.
     * @return When the earbuds go back, as a short sentence for the user.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun ambientForAMoment(minutes: Int): String {
        if (minutes !in 1..30) throw AppFunctionInvalidArgumentException("minutes must be between 1 and 30")
        return withLink {
            val from = Headphones.state.value.shownNc ?: SonyProtocol.NcState(SonyProtocol.Mode.ANC)
            val back = AmbientTimer.start(this, minutes, from)
            if (back != null) "Ambient sound for $minutes minutes, then back to ${back.label.lowercase()}."
            else notReachable()
        } ?: setupRequired
    }

    /**
     * Reads the battery of the user's Sony WF-1000XM5 earbuds and their charging case, and how long
     * the user can keep listening.
     *
     * @return The levels in percent and the listening time left.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun getEarbudsBattery(): EarbudsBattery {
        Headphones.init(this)
        val snapshot = if (Prefs.linked(this) == false) null else withLink {
            withTimeoutOrNull(5000) { if (Headphones.connectAndSync()) Headphones.queryBattery() }
            // Capture the live parts while this action still owns the control link.
            Headphones.state.value
        }
        val state = snapshot ?: Headphones.state.value.copy(liveBatteryParts = emptySet())
        return BatteryWords.forAssistant(state, Prefs.drainRate(this, state.nc?.mode))
    }

    private suspend fun confirmedMode(mode: SonyProtocol.Mode): Boolean {
        val state = Headphones.state.first { it.pending == null }
        return state.ncConfirmed && state.nc?.mode == mode
    }

    private fun modeOf(name: String) = when (name.lowercase()) {
        "noise_cancelling", "anc", "noise cancelling" -> SonyProtocol.Mode.ANC
        "ambient", "ambient_sound", "transparency" -> SonyProtocol.Mode.AMBIENT
        "off" -> SonyProtocol.Mode.OFF
        else -> throw AppFunctionInvalidArgumentException("mode must be noise_cancelling, ambient or off")
    }

    private fun notReachable() =
        if (Prefs.linked(this) == false) "The earbuds are in their case. Take them out first."
        else "The earbuds didn't answer. Make sure they're connected to this phone."

    /** A background lease, as the widget takes for a tap. */
    private suspend fun <T : Any> withLink(block: suspend () -> T): T? {
        Headphones.init(this)
        if (!Headphones.hasPermission(this) || Prefs.deviceAddress(this) == null)
            return null
        Headphones.hold(foreground = false)
        try { return block() } finally { Headphones.release(foreground = false) }
    }
}

/** Current earbud and case battery for an assistant. A cached level may appear in the summary. */
@AppFunctionSerializable(isDescribedByKDoc = true)
class EarbudsBattery(
    /** Current left earbud level, percent, or -1 when unavailable. */
    val leftPercent: Int,
    /** Current right earbud level, percent, or -1 when unavailable. */
    val rightPercent: Int,
    /** Current charging case level, percent, or -1 when unavailable. */
    val casePercent: Int,
    /** True while the earbuds are charging in the case. */
    val charging: Boolean,
    /** Minutes of listening left on the emptier earbud, or -1 when unknown. */
    val listeningMinutesLeft: Int,
    /** Current readings, with any cached levels explicitly identified as last known. */
    val summary: String,
)

/** Plain-language battery for voice answers. Pure, unit-tested. */
object BatteryWords {
    internal fun forAssistant(state: DeviceState, rate: Double?): EarbudsBattery {
        val known = state.battery ?: SonyProtocol.Battery()
        val fresh = state.liveBatteryParts
        val live = SonyProtocol.Battery(
            left = known.left.takeIf { 0 in fresh },
            right = known.right.takeIf { 1 in fresh },
            case = known.case.takeIf { 2 in fresh },
            leftCharging = known.leftCharging && 0 in fresh,
            rightCharging = known.rightCharging && 1 in fresh,
        )
        val charging = live.leftCharging || live.rightCharging
        val minutes = BatteryLife.budsMinutes(live, state.nc?.mode, rate).takeUnless { charging }
        val current = when {
            live.left != null || live.right != null -> sentence(live, minutes?.let(BatteryLife::short), linked = true)
            live.case != null -> "Case battery ${live.case}%."
            else -> null
        }
        val history = listOfNotNull(
            known.left?.takeUnless { 0 in fresh }?.let { "left earbud $it%" },
            known.right?.takeUnless { 1 in fresh }?.let { "right earbud $it%" },
            known.case?.takeUnless { 2 in fresh }?.let { "case $it%" },
        ).takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "Last known readings: ", postfix = ".")
        val summary = listOfNotNull(current, history).joinToString(" ").ifEmpty { sentence(known, null, linked = false) }
        return EarbudsBattery(live.left ?: -1, live.right ?: -1, live.case ?: -1, charging, minutes ?: -1, summary)
    }

    fun sentence(b: SonyProtocol.Battery, timeLeft: String?, linked: Boolean): String {
        val buds = when {
            b.left != null && b.right != null && b.left == b.right -> "Both earbuds are at ${b.left}%"
            b.left != null && b.right != null -> "Left earbud ${b.left}%, right ${b.right}%"
            b.left != null -> "Left earbud ${b.left}%"
            b.right != null -> "Right earbud ${b.right}%"
            else -> return "The earbuds' battery isn't known yet. Take them out of the case."
        }
        val case = b.case?.let { ", the case $it%" } ?: ""
        val time = when {
            b.leftCharging || b.rightCharging -> ". Charging."
            linked && timeLeft != null -> ". About $timeLeft of listening left."
            else -> "."
        }
        return buds + case + time
    }
}
