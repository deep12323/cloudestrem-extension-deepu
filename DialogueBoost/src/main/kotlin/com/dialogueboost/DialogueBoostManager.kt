package com.dialogueboost

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.utils.DataStore
import com.lagradost.cloudstream3.utils.DataStore.setKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

object DialogueBoostManager {

    private const val TAG = "DialogueBoost"
    private const val PREFS_NAME = "dialogue_boost_plugin_prefs"

    // Extension internal settings keys
    const val KEY_ALWAYS_ENABLED = "dialogue_boost_always_enabled"
    const val KEY_PRESET = "dialogue_boost_preset"
    const val KEY_CUSTOM_THRESHOLD = "dialogue_boost_threshold"
    const val KEY_CUSTOM_MAKEUP = "dialogue_boost_makeup"
    const val KEY_CUSTOM_RATIO = "dialogue_boost_ratio"
    const val KEY_CUSTOM_ATTACK = "dialogue_boost_attack"
    const val KEY_CUSTOM_RELEASE = "dialogue_boost_release"

    // Cloudstream internal keys (from DynamicRangeCompressor & FullScreenPlayer)
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
    private var isLifecycleRegistered = false
    private var currentActivityRef: WeakReference<Activity>? = null
    private var appContext: Context? = null

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
     * Resets DialogueBoost to recommended defaults (-24 dB threshold, +12 dB makeup gain, enabled).
     */
    fun resetToDefaults(context: Context) {
        setAlwaysEnabled(context, true)
        setPreset(context, PRESET_DIALOGUE)
        setCustomThreshold(context, -24f)
        setCustomMakeup(context, 12f)
        setCustomRatio(context, 8f)
        enforceDiskSettings(context)
        enforceActivePlayer()
    }

    /**
     * Completely disables DialogueBoost in settings, disk, and live player.
     */
    fun turnOff(context: Context) {
        setAlwaysEnabled(context, false)
        enforceDiskSettings(context)
        enforceActivePlayer()
    }

    /**
     * Initializes hooks and background enforcement.
     */
    fun init(context: Context) {
        val app = (context.applicationContext as? Application) ?: (context as? Application)
        appContext = app ?: context.applicationContext ?: context

        // Unpack activity if context is an Activity
        findActivityFromContext(context)?.let {
            currentActivityRef = WeakReference(it)
        }

        if (app != null && !isLifecycleRegistered) {
            isLifecycleRegistered = true
            registerLifecycle(app)
        }

        // Run initial disk enforcement immediately
        if (isAlwaysEnabled(context)) {
            enforceDiskSettings(context)
        }

        // Start active memory monitoring daemon
        startAutoEnforceDaemon(context)
    }

    private fun findActivityFromContext(context: Context?): Activity? {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    private fun registerLifecycle(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                currentActivityRef = WeakReference(activity)
            }

            override fun onActivityStarted(activity: Activity) {
                currentActivityRef = WeakReference(activity)
                enforceActivePlayer(activity)
            }

            override fun onActivityResumed(activity: Activity) {
                currentActivityRef = WeakReference(activity)
                enforceActivePlayer(activity)
                appContext?.let { enforceDiskSettings(it) }
            }

            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                if (currentActivityRef?.get() === activity) {
                    currentActivityRef = null
                }
            }
        })
    }

    /**
     * Finds foreground Activity via CommonActivity, registered ref, or context traversal.
     */
    fun getForegroundActivity(): Activity? {
        // 1. Primary: CloudStream's own CommonActivity.activity
        runCatching {
            val act = CommonActivity.activity
            if (act != null && !act.isFinishing && !act.isDestroyed) {
                currentActivityRef = WeakReference(act)
                return act
            }
        }

        // 2. Reflection on CommonActivity (in case of classloader isolation)
        runCatching {
            val clazz = Class.forName("com.lagradost.cloudstream3.CommonActivity")
            val act = runCatching {
                val field = clazz.getDeclaredField("INSTANCE").apply { isAccessible = true }
                val instance = field.get(null)
                clazz.getMethod("getActivity").invoke(instance) as? Activity
            }.getOrNull() ?: runCatching {
                clazz.getMethod("getActivity").invoke(null) as? Activity
            }.getOrNull()
            if (act != null && !act.isFinishing && !act.isDestroyed) {
                currentActivityRef = WeakReference(act)
                return act
            }
        }

        // 3. Fallback: Lifecycle callback cached ref
        val refAct = currentActivityRef?.get()
        if (refAct != null && !refAct.isFinishing && !refAct.isDestroyed) {
            return refAct
        }

        // 4. Fallback: App context unwrap
        appContext?.let {
            val unwrapped = findActivityFromContext(it)
            if (unwrapped != null && !unwrapped.isFinishing && !unwrapped.isDestroyed) {
                currentActivityRef = WeakReference(unwrapped)
                return unwrapped
            }
        }

        return null
    }

    /**
     * Enforces the dynamic range compressor settings directly into Cloudstream's
     * SharedPreferences and DataStore on disk using synchronous commit().
     */
    fun enforceDiskSettings(context: Context): Boolean {
        return runCatching {
            val alwaysOn = isAlwaysEnabled(context)
            val threshold = getThreshold(context)
            val makeup = getMakeup(context)
            val ratio = getRatio(context)
            val attack = getAttackMs(context)
            val release = getReleaseMs(context)

            // 1. CloudStream App-level settings (PreferenceManager)
            // This is CRUCIAL because CS3IPlayer reads:
            // settingsManager.getBoolean("compressor_enabled_key", false)
            // to decide whether to even instantiate DynamicRangeCompressor!
            runCatching {
                getDefaultSharedPreferences(context).edit()
                    .putBoolean(CS_COMPRESSOR_ENABLED_KEY, alwaysOn)
                    .putBoolean(CS_PLAYER_COMPRESSOR_ENABLED, alwaysOn)
                    .commit()
            }

            // 2. CloudStream DataStore ("rebuild_preference") via DataStore extension
            runCatching {
                context.setKey(CS_PLAYER_COMPRESSOR_ENABLED, alwaysOn)
                context.setKey(CS_PLAYER_COMPRESSOR_THRESHOLD, threshold)
                context.setKey(CS_PLAYER_COMPRESSOR_MAKEUP, makeup)
                context.setKey(CS_PLAYER_COMPRESSOR_RATIO, ratio)
                context.setKey(CS_PLAYER_COMPRESSOR_ATTACK, attack)
                context.setKey(CS_PLAYER_COMPRESSOR_RELEASE, release)
            }

            // 3. CloudStreamApp companion setKey
            runCatching {
                CloudStreamApp.setKey(CS_PLAYER_COMPRESSOR_ENABLED, alwaysOn)
                CloudStreamApp.setKey(CS_PLAYER_COMPRESSOR_THRESHOLD, threshold)
                CloudStreamApp.setKey(CS_PLAYER_COMPRESSOR_MAKEUP, makeup)
                CloudStreamApp.setKey(CS_PLAYER_COMPRESSOR_RATIO, ratio)
                CloudStreamApp.setKey(CS_PLAYER_COMPRESSOR_ATTACK, attack)
                CloudStreamApp.setKey(CS_PLAYER_COMPRESSOR_RELEASE, release)
            }

            // 4. Raw SharedPreferences writes to "rebuild_preference"
            // FullScreenPlayer.restoreCompressorSettings() deserializes JSON strings
            val rebuildPrefs = context.getSharedPreferences("rebuild_preference", Context.MODE_PRIVATE)
            rebuildPrefs.edit()
                .putString(CS_PLAYER_COMPRESSOR_ENABLED, if (alwaysOn) "true" else "false")
                .putString(CS_PLAYER_COMPRESSOR_THRESHOLD, threshold.toString())
                .putString(CS_PLAYER_COMPRESSOR_MAKEUP, makeup.toString())
                .putString(CS_PLAYER_COMPRESSOR_RATIO, ratio.toString())
                .putString(CS_PLAYER_COMPRESSOR_ATTACK, attack.toString())
                .putString(CS_PLAYER_COMPRESSOR_RELEASE, release.toString())
                .putBoolean(CS_PLAYER_COMPRESSOR_ENABLED, alwaysOn)
                .putFloat(CS_PLAYER_COMPRESSOR_THRESHOLD, threshold)
                .putFloat(CS_PLAYER_COMPRESSOR_MAKEUP, makeup)
                .putFloat(CS_PLAYER_COMPRESSOR_RATIO, ratio)
                .commit()

            true
        }.getOrDefault(false)
    }

    /**
     * Checks if a fragment is an active player fragment:
     * - GeneratorPlayer (CloudStream's video player runtime fragment)
     * - FullScreenPlayer
     * - AbstractPlayerFragment
     * - Any fragment class inheriting from PlayerFragment / FullScreenPlayer
     */
    fun isPlayerFragment(f: Any): Boolean {
        var cls: Class<*>? = f.javaClass
        while (cls != null && cls != Any::class.java) {
            val name = cls.name
            if (name.endsWith("GeneratorPlayer") ||
                name.endsWith("FullScreenPlayer") ||
                name.endsWith("AbstractPlayerFragment") ||
                name.contains("PlayerFragment")) {
                return true
            }
            cls = cls.superclass
        }
        return false
    }

    /**
     * Scans for the active player fragment / PlayerView in memory and directly
     * configures and enables the DynamicRangeCompressor instance in ExoPlayer.
     */
    fun enforceActivePlayer(activity: Activity? = null): Boolean {
        val act = activity ?: getForegroundActivity() ?: return false
        val r1 = findCompressorFromFragments(act)
        val r2 = findCompressorFromViews(act)
        return r1 || r2
    }

    private fun findCompressorFromFragments(activity: Activity): Boolean {
        var activated = false
        runCatching {
            val fm = runCatching {
                val method = activity.javaClass.methods.firstOrNull { it.name == "getSupportFragmentManager" && it.parameterTypes.isEmpty() }
                method?.invoke(activity)
            }.getOrNull() ?: return false

            fun scanFm(fragmentManager: Any) {
                val getFragmentsMethod = fragmentManager.javaClass.methods.firstOrNull { it.name == "getFragments" && it.parameterTypes.isEmpty() } ?: return
                val fragments = (getFragmentsMethod.invoke(fragmentManager) as? List<*>) ?: return
                for (f in fragments) {
                    if (f == null) continue
                    if (isPlayerFragment(f)) {
                        // 1. Force playBackCompressorEnabled on player fragment
                        setMember(f, "playBackCompressorEnabled", isAlwaysEnabled(activity))

                        // 2. Invoke restoreCompressorSettings()
                        runCatching {
                            var targetClass: Class<*>? = f.javaClass
                            while (targetClass != null && targetClass != Any::class.java) {
                                val restoreMethod = targetClass.declaredMethods.firstOrNull { it.name == "restoreCompressorSettings" }
                                if (restoreMethod != null) {
                                    restoreMethod.isAccessible = true
                                    restoreMethod.invoke(f)
                                    break
                                }
                                targetClass = targetClass.superclass
                            }
                        }

                        // 3. Resolve active CS3IPlayer instance:
                        // Calling getPlayer() executes playerHostView?.player ?: _player
                        val player = runCatching {
                            val method = f.javaClass.methods.firstOrNull { it.name == "getPlayer" && it.parameterTypes.isEmpty() }
                            method?.invoke(f)
                        }.getOrNull()
                            ?: getMember(f, "player")
                            ?: getMember(f, "playerHostView")?.let { getMember(it, "player") }
                            ?: getMember(f, "_player")

                        if (player != null) {
                            val compressor = getMember(player, "compressor")
                            if (compressor != null) {
                                if (applyCompressorDirectly(compressor, activity)) {
                                    activated = true
                                    Log.i(TAG, "Successfully enforced DialogueBoost compressor on active player fragment ($f)")
                                }
                            }
                        }
                    }

                    // Recurse childFragmentManager
                    runCatching {
                        val getChildFmMethod = f.javaClass.methods.firstOrNull { it.name == "getChildFragmentManager" && it.parameterTypes.isEmpty() }
                        val childFm = getChildFmMethod?.invoke(f)
                        if (childFm != null) {
                            scanFm(childFm)
                        }
                    }
                }
            }

            scanFm(fm)
        }
        return activated
    }

    private fun findCompressorFromViews(activity: Activity): Boolean {
        var activated = false
        runCatching {
            val decorView = activity.window?.decorView ?: return false
            val playerViews = mutableListOf<View>()
            fun collectViews(view: View) {
                if (view.javaClass.name.contains("PlayerView")) {
                    playerViews.add(view)
                }
                if (view is ViewGroup) {
                    for (i in 0 until view.childCount) {
                        val child = view.getChildAt(i) ?: continue
                        collectViews(child)
                    }
                }
            }
            collectViews(decorView)

            for (pv in playerViews) {
                val player = getMember(pv, "player")
                if (player != null) {
                    val compressor = getMember(player, "compressor")
                    if (compressor != null) {
                        if (applyCompressorDirectly(compressor, activity)) {
                            activated = true
                            Log.i(TAG, "Successfully enforced DialogueBoost compressor from PlayerView ($pv)")
                        }
                    }
                }
            }
        }
        return activated
    }

    /**
     * Directly manipulates the in-memory DynamicRangeCompressor instance.
     */
    private fun applyCompressorDirectly(compressor: Any, context: Context): Boolean {
        val alwaysOn = isAlwaysEnabled(context)
        val targetThreshold = getThreshold(context)
        val targetMakeup = getMakeup(context)
        val targetRatio = getRatio(context)
        val targetAttack = getAttackMs(context)
        val targetRelease = getReleaseMs(context)

        var changed = false

        // Check & apply enabled
        val currentEnabled = getMember(compressor, "enabled") as? Boolean
        if (currentEnabled != alwaysOn) {
            setMember(compressor, "enabled", alwaysOn)
            changed = true
            Log.i(TAG, "Flipped in-memory compressor enabled: $currentEnabled -> $alwaysOn")
        }

        // Check & apply threshold
        val currentThreshold = (getMember(compressor, "threshold") as? Number)?.toFloat()
        if (currentThreshold != targetThreshold) {
            setMember(compressor, "threshold", targetThreshold)
            changed = true
        }

        // Check & apply makeupGain
        val currentMakeup = (getMember(compressor, "makeupGain") as? Number)?.toFloat()
        if (currentMakeup != targetMakeup) {
            setMember(compressor, "makeupGain", targetMakeup)
            changed = true
        }

        // Check & apply ratio
        val currentRatio = (getMember(compressor, "ratio") as? Number)?.toFloat()
        if (currentRatio != targetRatio) {
            setMember(compressor, "ratio", targetRatio)
            changed = true
        }

        // Check & apply attack/release
        setMember(compressor, "attackMs", targetAttack)
        setMember(compressor, "releaseMs", targetRelease)

        return changed
    }

    private fun getMember(target: Any, name: String): Any? {
        // Try getter e.g. getCompressor()
        runCatching {
            val getterName = "get" + name.replaceFirstChar { it.uppercase() }
            val method = target.javaClass.methods.firstOrNull { it.name == getterName && it.parameterTypes.isEmpty() }
            if (method != null) {
                method.isAccessible = true
                return method.invoke(target)
            }
        }
        // Try method with identical name
        runCatching {
            val method = target.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
            if (method != null) {
                method.isAccessible = true
                return method.invoke(target)
            }
        }
        // Try field
        runCatching {
            var clazz: Class<*>? = target.javaClass
            while (clazz != null && clazz != Any::class.java) {
                val field = clazz.declaredFields.firstOrNull { it.name == name }
                if (field != null) {
                    field.isAccessible = true
                    return field.get(target)
                }
                clazz = clazz.superclass
            }
        }
        return null
    }

    private fun setMember(target: Any, name: String, value: Any): Boolean {
        // Try setter e.g. setEnabled(...)
        runCatching {
            val setterName = "set" + name.replaceFirstChar { it.uppercase() }
            val method = target.javaClass.methods.firstOrNull { it.name == setterName && it.parameterTypes.size == 1 }
            if (method != null) {
                method.isAccessible = true
                method.invoke(target, value)
                return true
            }
        }
        // Try direct field
        runCatching {
            var clazz: Class<*>? = target.javaClass
            while (clazz != null && clazz != Any::class.java) {
                val field = clazz.declaredFields.firstOrNull { it.name == name }
                if (field != null) {
                    field.isAccessible = true
                    field.set(target, value)
                    return true
                }
                clazz = clazz.superclass
            }
        }
        return false
    }

    /**
     * Starts a continuous, ultra-light background daemon that monitors video playback
     * and guarantees that newly instantiated compressors are immediately turned ON.
     */
    fun startAutoEnforceDaemon(context: Context) {
        if (isDaemonRunning) return
        isDaemonRunning = true

        CoroutineScope(Dispatchers.Main.immediate).launch {
            var diskSyncCounter = 0
            while (true) {
                try {
                    // Fast in-memory check and activation on Main thread
                    enforceActivePlayer()

                    // Periodic disk sync on IO dispatcher (every ~4 seconds)
                    diskSyncCounter++
                    if (diskSyncCounter >= 10) {
                        diskSyncCounter = 0
                        if (isAlwaysEnabled(context)) {
                            withContext(Dispatchers.IO) {
                                enforceDiskSettings(context)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Auto-enforce tick warning: ${e.message}")
                }
                delay(350) // Checks every 350ms for lightning-fast auto-activation
            }
        }
    }
}
