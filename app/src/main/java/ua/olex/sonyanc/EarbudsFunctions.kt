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
        }
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
            AmbientTimer.start(this, minutes, from)
            val ok = withTimeoutOrNull(8500) { confirmedMode(SonyProtocol.Mode.AMBIENT) } ?: false
            if (ok) "Ambient sound for $minutes minutes, then back to ${AmbientTimer.returnMode(this).label.lowercase()}."
            else { AmbientTimer.cancel(this); notReachable() }
        }
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
        if (Prefs.linked(this) != false) withLink {
            withTimeoutOrNull(5000) { if (Headphones.connectAndSync()) Headphones.queryBattery() }
            ""
        }
        val state = Headphones.state.value
        val b = state.battery ?: SonyProtocol.Battery()
        val mode = state.nc?.mode
        val left = BatteryLife.budsMinutes(b, mode, Prefs.drainRate(this, mode))
        return EarbudsBattery(
            leftPercent = b.left ?: -1, rightPercent = b.right ?: -1, casePercent = b.case ?: -1,
            charging = b.leftCharging || b.rightCharging,
            listeningMinutesLeft = left ?: -1,
            summary = BatteryWords.sentence(b, left?.let(BatteryLife::format), Prefs.linked(this) == true),
        )
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
    private suspend fun withLink(block: suspend () -> String): String {
        Headphones.init(this)
        if (!Headphones.hasPermission(this) || Prefs.deviceAddress(this) == null)
            return "Open Earbuds once to choose your WF-1000XM5."
        Headphones.hold(foreground = false)
        try { return block() } finally { Headphones.release(foreground = false) }
    }
}

/** The earbuds' battery for an assistant. -1 means not known. */
@AppFunctionSerializable(isDescribedByKDoc = true)
class EarbudsBattery(
    /** Left earbud, percent, or -1 when unknown. */
    val leftPercent: Int,
    /** Right earbud, percent, or -1 when unknown. */
    val rightPercent: Int,
    /** Charging case, percent, or -1 when unknown. */
    val casePercent: Int,
    /** True while the earbuds are charging in the case. */
    val charging: Boolean,
    /** Minutes of listening left on the emptier earbud, or -1 when unknown. */
    val listeningMinutesLeft: Int,
    /** The same as one sentence for the user. */
    val summary: String,
)

/** Plain-language battery for voice answers. Pure, unit-tested. */
object BatteryWords {
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
