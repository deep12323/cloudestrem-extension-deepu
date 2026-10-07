package com.skiptorrentwarning

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

object SkipTorrentWarningManager {

    private const val TAG = "SkipTorrentWarning"
    private const val PREFS_NAME = "skip_torrent_warning_prefs"
    const val KEY_AUTO_BYPASS_ENABLED = "auto_bypass_enabled"

    private var appContext: Context? = null
    private var currentActivityRef: WeakReference<Activity>? = null
    private var isLifecycleRegistered = false
    private var isDaemonRunning = false

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isAutoBypassEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTO_BYPASS_ENABLED, true)
    }

    fun setAutoBypassEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_BYPASS_ENABLED, enabled).apply()
        if (enabled) {
            enforceProactiveBypass(context)
        } else {
            resetSessionState(context)
        }
    }

    /**
     * Initializes the manager, registers lifecycle hooks, enforces the proactive
     * session bypass, and starts the background guardian daemon.
     */
    fun init(context: Context) {
        val app = (context.applicationContext as? Application) ?: (context as? Application)
        appContext = app ?: context.applicationContext ?: context

        findActivityFromContext(context)?.let {
            currentActivityRef = WeakReference(it)
        }

        if (app != null && !isLifecycleRegistered) {
            isLifecycleRegistered = true
            registerLifecycle(app)
        }

        // Apply proactive bypass immediately upon plugin load
        if (isAutoBypassEnabled(context)) {
            enforceProactiveBypass(context)
        }

        startDaemon(context)
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
                if (isAutoBypassEnabled(activity)) {
                    enforceProactiveBypass(activity)
                }
            }

            override fun onActivityStarted(activity: Activity) {
                currentActivityRef = WeakReference(activity)
                if (isAutoBypassEnabled(activity)) {
                    enforceProactiveBypass(activity)
                }
            }

            override fun onActivityResumed(activity: Activity) {
                currentActivityRef = WeakReference(activity)
                if (isAutoBypassEnabled(activity)) {
                    enforceProactiveBypass(activity)
                }
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

    fun getForegroundActivity(): Activity? {
        // 1. CloudStream CommonActivity
        runCatching {
            val act = CommonActivity.activity
            if (act != null && !act.isFinishing && !act.isDestroyed) {
                currentActivityRef = WeakReference(act)
                return act
            }
        }

        // 2. Reflection on CommonActivity
        runCatching {
            val clazz = Class.forName("com.lagradost.cloudstream3.CommonActivity")
            val instance = runCatching {
                clazz.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
            }.getOrNull()
            val act = runCatching {
                clazz.getMethod("getActivity").invoke(instance) as? Activity
            }.getOrNull() ?: runCatching {
                clazz.getMethod("getActivity").invoke(null) as? Activity
            }.getOrNull()

            if (act != null && !act.isFinishing && !act.isDestroyed) {
                currentActivityRef = WeakReference(act)
                return act
            }
        }

        // 3. Fallback to cached reference
        val cached = currentActivityRef?.get()
        if (cached != null && !cached.isFinishing && !cached.isDestroyed) {
            return cached
        }

        return null
    }

    /**
     * Proactive Session Bypass (Primary):
     * Directly sets `Torrent.hasAcceptedTorrentForThisSession = true` in CloudStream's
     * memory. When set to true, CS3IPlayer's check:
     *
     *   if (Torrent.hasAcceptedTorrentForThisSession == true) {
     *       loadTorrent(context, link)
     *       return
     *   }
     *
     * triggers immediately, completely bypassing the creation and display of
     * the "Stream Torrent" AlertDialog!
     */
    fun enforceProactiveBypass(context: Context? = null): Boolean {
        return setTorrentAcceptedSessionValue(true, context)
    }

    /**
     * Resets the session state back to null (unaccepted).
     */
    fun resetSessionState(context: Context? = null): Boolean {
        return setTorrentAcceptedSessionValue(null, context)
    }

    private fun setTorrentAcceptedSessionValue(targetValue: Boolean?, context: Context?): Boolean {
        val targetCtx = context ?: appContext
        val classLoaders = listOfNotNull(
            targetCtx?.classLoader,
            Thread.currentThread().contextClassLoader,
            SkipTorrentWarningManager::class.java.classLoader
        ).distinct()

        var succeeded = false

        for (cl in classLoaders) {
            val success = runCatching {
                val torrentClass = Class.forName("com.lagradost.cloudstream3.ui.player.Torrent", true, cl)

                // Get singleton INSTANCE if Kotlin object
                val instance = runCatching {
                    torrentClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
                }.getOrNull()

                // 1. Try instance setter: setHasAcceptedTorrentForThisSession(Boolean)
                val setter = torrentClass.methods.firstOrNull {
                    it.name == "setHasAcceptedTorrentForThisSession" && it.parameterTypes.size == 1
                } ?: torrentClass.declaredMethods.firstOrNull {
                    it.name == "setHasAcceptedTorrentForThisSession" && it.parameterTypes.size == 1
                }

                if (setter != null) {
                    setter.isAccessible = true
                    if (instance != null) {
                        runCatching { setter.invoke(instance, targetValue); succeeded = true }
                    }
                    runCatching { setter.invoke(null, targetValue); succeeded = true }
                }

                // 2. Direct field manipulation (both on instance and statically)
                var currClass: Class<*>? = torrentClass
                while (currClass != null && currClass != Any::class.java) {
                    val field = runCatching {
                        currClass.getDeclaredField("hasAcceptedTorrentForThisSession").apply { isAccessible = true }
                    }.getOrNull()

                    if (field != null) {
                        if (instance != null) {
                            runCatching { field.set(instance, targetValue); succeeded = true }
                        }
                        runCatching { field.set(null, targetValue); succeeded = true }
                        break
                    }
                    currClass = currClass.superclass
                }

                succeeded
            }.getOrDefault(false)

            if (success) {
                Log.d(TAG, "Successfully updated Torrent.hasAcceptedTorrentForThisSession to $targetValue")
                break
            }
        }

        return succeeded
    }

    /**
     * Reads current in-memory value of `Torrent.hasAcceptedTorrentForThisSession`.
     * Returns true, false, or null.
     */
    fun getCurrentTorrentSessionState(context: Context? = null): Boolean? {
        val targetCtx = context ?: appContext
        val classLoaders = listOfNotNull(
            targetCtx?.classLoader,
            Thread.currentThread().contextClassLoader,
            SkipTorrentWarningManager::class.java.classLoader
        ).distinct()

        for (cl in classLoaders) {
            val state = runCatching {
                val torrentClass = Class.forName("com.lagradost.cloudstream3.ui.player.Torrent", true, cl)
                val instance = runCatching {
                    torrentClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
                }.getOrNull()

                // Try getter
                val getter = torrentClass.methods.firstOrNull {
                    it.name == "getHasAcceptedTorrentForThisSession" && it.parameterTypes.isEmpty()
                } ?: torrentClass.declaredMethods.firstOrNull {
                    it.name == "getHasAcceptedTorrentForThisSession" && it.parameterTypes.isEmpty()
                }

                if (getter != null) {
                    getter.isAccessible = true
                    val res = (getter.invoke(instance) ?: getter.invoke(null)) as? Boolean
                    if (res != null) return@runCatching res
                }

                // Try field
                var currClass: Class<*>? = torrentClass
                while (currClass != null && currClass != Any::class.java) {
                    val field = runCatching {
                        currClass.getDeclaredField("hasAcceptedTorrentForThisSession").apply { isAccessible = true }
                    }.getOrNull()
                    if (field != null) {
                        val value = (field.get(instance) ?: field.get(null)) as? Boolean
                        if (value != null) return@runCatching value
                    }
                    currClass = currClass.superclass
                }
                null
            }.getOrNull()

            if (state != null) return state
        }
        return null
    }

    /**
     * Secondary Safety Net (Reactive Dialog Interceptor):
     * If an AlertDialog with the torrent warning is ever constructed or shown
     * on screen, automatically simulate clicking the positive "OK" button
     * so it never hangs or blocks the user.
     */
    private fun autoDismissWarningDialog(activity: Activity): Boolean {
        return runCatching {
            val decorView = activity.window?.decorView ?: return false
            var clicked = false

            fun scanView(view: View): Boolean {
                if (clicked) return true

                // Check text views for warning content
                if (view is TextView) {
                    val text = view.text?.toString() ?: ""
                    val isTorrentWarning = (text.contains("Stream Torrent", ignoreCase = true) ||
                            (text.contains("Torrent", ignoreCase = true) && text.contains("tracked", ignoreCase = true)))

                    if (isTorrentWarning) {
                        // Found torrent warning popup! Find positive button in parent tree
                        val root = view.rootView ?: return false
                        val positiveBtn = root.findViewById<Button>(android.R.id.button1)
                        if (positiveBtn != null && positiveBtn.isShown) {
                            positiveBtn.performClick()
                            clicked = true
                            Log.i(TAG, "Reactive safety net: Auto-clicked 'OK' on torrent warning dialog")
                            return true
                        }
                    }
                }

                if (view is ViewGroup) {
                    for (i in 0 until view.childCount) {
                        val child = view.getChildAt(i) ?: continue
                        if (scanView(child)) return true
                    }
                }
                return false
            }

            scanView(decorView)
            clicked
        }.getOrDefault(false)
    }

    /**
     * Background guardian daemon:
     * 1. Continuously keeps `Torrent.hasAcceptedTorrentForThisSession = true`.
     * 2. Inspects foreground window for any warning dialog that might pop up.
     */
    fun startDaemon(context: Context) {
        if (isDaemonRunning) return
        isDaemonRunning = true

        CoroutineScope(Dispatchers.Main.immediate).launch {
            while (isDaemonRunning) {
                try {
                    if (isAutoBypassEnabled(context)) {
                        // 1. Proactive bypass enforcement
                        if (getCurrentTorrentSessionState(context) != true) {
                            enforceProactiveBypass(context)
                        }

                        // 2. Reactive safety net check
                        getForegroundActivity()?.let { act ->
                            autoDismissWarningDialog(act)
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Bypass daemon tick warning: ${e.message}")
                }
                delay(750)
            }
        }
    }

    fun stopDaemon() {
        isDaemonRunning = false
    }

    fun cleanup() {
        stopDaemon()
    }
}
