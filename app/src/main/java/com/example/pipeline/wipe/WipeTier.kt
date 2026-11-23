package com.example.pipeline.wipe

import android.content.Context
import android.content.SharedPreferences

/** User-selectable wipe quality tier (prompt 20261118, Step 1). */
enum class WipeTier { STANDARD, BEST }

enum class WipeTierMode { AUTO, STANDARD, BEST }

/**
 * Tier selection: stored user override (AUTO by default), resolved
 * against device capability. Capability rule: Best needs the optional
 * LaMa inpaint model downloaded AND enough RAM to hold it next to the
 * CTD session (measured PSS about 1 GB for CTD on emulator); devices
 * below the RAM floor resolve to Standard in AUTO. An explicit BEST
 * without the model falls back to Standard at the wipe site, logged.
 */
object WipeTierStore {
    private const val PREFS = "wipe_tier"
    private const val KEY = "mode"
    const val BEST_MIN_RAM_MB = 6_000L

    fun mode(context: Context): WipeTierMode {
        val prefs = prefs(context)
        return runCatching { WipeTierMode.valueOf(prefs.getString(KEY, WipeTierMode.AUTO.name)!!) }
            .getOrDefault(WipeTierMode.AUTO)
    }

    fun setMode(context: Context, mode: WipeTierMode) {
        prefs(context).edit().putString(KEY, mode.name).apply()
    }

    fun resolve(mode: WipeTierMode, totalRamMb: Long, lamaDownloaded: Boolean): WipeTier =
        when (mode) {
            WipeTierMode.STANDARD -> WipeTier.STANDARD
            WipeTierMode.BEST -> WipeTier.BEST
            WipeTierMode.AUTO ->
                if (lamaDownloaded && totalRamMb >= BEST_MIN_RAM_MB) WipeTier.BEST else WipeTier.STANDARD
        }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
