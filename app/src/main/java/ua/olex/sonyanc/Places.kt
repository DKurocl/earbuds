package ua.olex.sonyanc

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "At home": a mode of its own when the earbuds connect at home (say Ambient), the usual rule
 * everywhere else. No tracking and no background work: at the moment the earbuds connect, the
 * phone's last known location (Android keeps it current anyway) is compared with the saved home.
 * A stale or missing location simply means the usual rule.
 */
object Places {
    /** Home is a circle of this radius around the saved point. */
    const val RADIUS_M = 250.0
    /** A location older than this says nothing about where the phone is now. */
    private const val FRESH_MS = 60 * 60_000L

    /** Great-circle distance in metres. Pure, unit-tested. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(min(1.0, sqrt(a)))
    }

    /** Inside home, allowing for the fix's own uncertainty (at most 500 m of it). Pure, unit-tested. */
    fun isHome(homeLat: Double, homeLon: Double, lat: Double, lon: Double, accuracyM: Float): Boolean =
        distanceM(homeLat, homeLon, lat, lon) - min(accuracyM.toDouble(), 500.0) / 2 <= RADIUS_M

    fun hasLocation(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun hasBackground(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Whether the phone is at home now, from a fresh last-known fix; null when it can't tell. */
    fun atHome(ctx: Context): Boolean? {
        val home = Prefs.home(ctx) ?: return null
        if (!hasLocation(ctx) || !hasBackground(ctx)) return null
        val here = lastLocation(ctx)?.takeIf { System.currentTimeMillis() - it.time <= FRESH_MS } ?: return null
        return isHome(home.first, home.second, here.latitude, here.longitude, here.accuracy)
    }

    /** The mode to apply as the earbuds connect: home's at home, otherwise "When earbuds connect". */
    fun modeOnConnect(ctx: Context): SonyProtocol.Mode? {
        val usual = Prefs.onConnect(ctx).mode
        val atHome = Prefs.homeMode(ctx) ?: return usual
        val home = Prefs.home(ctx) ?: return usual
        if (!hasLocation(ctx) || !hasBackground(ctx)) return usual
        val here = lastLocation(ctx)?.takeIf { System.currentTimeMillis() - it.time <= FRESH_MS } ?: return usual
        return if (isHome(home.first, home.second, here.latitude, here.longitude, here.accuracy)) atHome.mode else usual
    }

    @SuppressLint("MissingPermission") // checked by the callers through hasLocation()
    private fun lastLocation(ctx: Context): Location? {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        return runCatching { lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time } }.getOrNull()
    }

    /** One fresh fix for "set home to where you are now". */
    @SuppressLint("MissingPermission")
    fun currentLocation(ctx: Context, onResult: (Location?) -> Unit) {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return onResult(null)
        if (!hasLocation(ctx)) return onResult(null)
        val provider = if (lm.hasProvider(LocationManager.FUSED_PROVIDER)) LocationManager.FUSED_PROVIDER else LocationManager.NETWORK_PROVIDER
        runCatching { lm.getCurrentLocation(provider, null, ctx.mainExecutor) { onResult(it ?: lastLocation(ctx)) } }
            .onFailure { onResult(lastLocation(ctx)) }
    }
}
