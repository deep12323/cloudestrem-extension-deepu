package com.dialogueboost

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object DialogueBoostManager {

    private const val PREFS_NAME = "dialogue_boost_plugin_prefs"

    // Extension internal settings keys
    const val KEY_ALWAYS_ENABLED = "dialogue_boost_always_enabled"
    const val KEY_PRESET = "dialogue_boost_preset"
    const val KEY_CUSTOM_THRESHOLD = "dialogue_boost_threshold"
    const val KEY_CUSTOM_MAKEUP = "dialogue_boost_makeup"
    const val KEY_CUSTOM_RATIO = "dialogue_boost_ratio"
    const val KEY_CUSTOM_ATTACK = "dialogue_boost_attack"
    const val KEY_CUSTOM_RELEASE = "dialogue_boost_release"

    // Cloudstream internal keys (from PR #3117 DynamicRangeCompressor)
    const val CS_COMPRESSOR_ENABLED_KEY = "compressor_enabled_key"
    const val CS_PLAYER_COMPRESSOR_ENABLED = "player_compressor_enabled"
    const val CS_PLAYER_COMPRESSOR_THRESHOLD = "player_compressor_threshold"
    const val CS_PLAYER_COMPRESSOR_MAKEUP = "player_compressor_makeup"
    const val CS_PLAYER_COMPRESSOR_RATIO = "player_compressor_ratio"
    const val CS_PLAYER_COMPRESSOR_ATTACK = "player_compressor_attack"
    const val CS_PLAYER_COMPRESSOR_RELEASE = "player_compressor_release"

    // Presets
    const val PRESET_DIALOGUE = "dialogue"   // -24 dB, +12 dB, 8:1 (Recommended)
    const val PRESET_NIGHT = "night"         // -30 dB, +16 dB, 12:1 (Heavy Action Limiting)
    const val PRESET_LIGHT = "light"         // -18 dB, +4 dB, 4:1 (Gentle / Headphones)
    const val PRESET_CUSTOM = "custom"

    private var isDaemonRunning = false

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getDefaultSharedPreferences(context: Context): SharedPreferences {
        return try {
            @Suppress("DEPRECATION")
            android.preference.PreferenceManager.getDefaultSharedPreferences(context)
        } catch (_: Throwable) {
            context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
        }
    }

    fun isAlwaysEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ALWAYS_ENABLED, true)
    }

    fun setAlwaysEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ALWAYS_ENABLED, enabled).apply()
    }

    fun getPreset(context: Context): String {
        return getPrefs(context).getString(KEY_PRESET, PRESET_DIALOGUE) ?: PRESET_DIALOGUE
    }

    fun setPreset(context: Context, preset: String) {
        getPrefs(context).edit().putString(KEY_PRESET, preset).apply()
    }

    fun getThreshold(context: Context): Float {
        return when (getPreset(context)) {
            PRESET_DIALOGUE -> -24f
            PRESET_NIGHT -> -30f
            PRESET_LIGHT -> -18f
            else -> getPrefs(context).getFloat(KEY_CUSTOM_THRESHOLD, -24f)
        }
    }

    fun setCustomThreshold(context: Context, value: Float) {
        getPrefs(context).edit().putFloat(KEY_CUSTOM_THRESHOLD, value).apply()
    }

    fun getMakeup(context: Context): Float {
        return when (getPreset(context)) {
            PRESET_DIALOGUE -> 12f
            PRESET_NIGHT -> 16f
            PRESET_LIGHT -> 4f
            else -> getPrefs(context).getFloat(KEY_CUSTOM_MAKEUP, 12f)
        }
    }

    fun setCustomMakeup(context: Context, value: Float) {
        getPrefs(context).edit().putFloat(KEY_CUSTOM_MAKEUP, value).apply()
    }

    fun getRatio(context: Context): Float {
        return when (getPreset(context)) {
            PRESET_DIALOGUE -> 8f
            PRESET_NIGHT -> 12f
            PRESET_LIGHT -> 4f
            else -> getPrefs(context).getFloat(KEY_CUSTOM_RATIO, 8f)
        }
    }

    fun setCustomRatio(context: Context, value: Float) {
        getPrefs(context).edit().putFloat(KEY_CUSTOM_RATIO, value).apply()
    }

    fun getAttackMs(context: Context): Float {
        return getPrefs(context).getFloat(KEY_CUSTOM_ATTACK, 5f)
    }

    fun getReleaseMs(context: Context): Float {
        return getPrefs(context).getFloat(KEY_CUSTOM_RELEASE, 400f)
    }

    /**
     * Enforces the dynamic range compressor settings directly into Cloudstream's
     * SharedPreferences and DataStore.
     */
    fun enforceCompressorSettings(context: Context): Boolean {
        return runCatching {
            val alwaysOn = isAlwaysEnabled(context)
            val threshold = getThreshold(context)
            val makeup = getMakeup(context)
            val ratio = getRatio(context)
            val attack = getAttackMs(context)
            val release = getReleaseMs(context)

            // 1. App-level settings (PreferenceManager)
            getDefaultSharedPreferences(context).edit()
                .putBoolean(CS_COMPRESSOR_ENABLED_KEY, alwaysOn)
                .apply()

            // 2. Cloudstream DataStore ("rebuild_preference")
            val rebuildPrefs = context.getSharedPreferences("rebuild_preference", Context.MODE_PRIVATE)
            rebuildPrefs.edit()
                .putString(CS_PLAYER_COMPRESSOR_ENABLED, if (alwaysOn) "true" else "false")
                .putString(CS_PLAYER_COMPRESSOR_THRESHOLD, threshold.toString())
                .putString(CS_PLAYER_COMPRESSOR_MAKEUP, makeup.toString())
                .putString(CS_PLAYER_COMPRESSOR_RATIO, ratio.toString())
                .putString(CS_PLAYER_COMPRESSOR_ATTACK, attack.toString())
                .putString(CS_PLAYER_COMPRESSOR_RELEASE, release.toString())
                .apply()

            // 3. Attempt dynamic reflection on DataStore as an additional safeguard
            runCatching {
                val dataStoreHelper = Class.forName("com.lagradost.cloudstream3.utils.DataStore")
                val methods = dataStoreHelper.declaredMethods
                val setKeyMethod = methods.firstOrNull { it.name == "setKey" && it.parameterTypes.size == 3 }
                if (setKeyMethod != null) {
                    setKeyMethod.isAccessible = true
                    setKeyMethod.invoke(null, context, CS_PLAYER_COMPRESSOR_ENABLED, alwaysOn)
                    setKeyMethod.invoke(null, context, CS_PLAYER_COMPRESSOR_THRESHOLD, threshold)
                    setKeyMethod.invoke(null, context, CS_PLAYER_COMPRESSOR_MAKEUP, makeup)
                    setKeyMethod.invoke(null, context, CS_PLAYER_COMPRESSOR_RATIO, ratio)
                }
            }

            true
        }.getOrDefault(false)
    }

    /**
     * Starts a light daemon to guarantee settings remain enforced across
     * view navigations and player instances.
     */
    fun startAutoEnforceDaemon(context: Context) {
        if (isDaemonRunning) return
        isDaemonRunning = true

        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                try {
                    if (isAlwaysEnabled(context)) {
                        enforceCompressorSettings(context)
                    }
                } catch (_: Throwable) {}
                delay(8000)
            }
        }
    }
}
