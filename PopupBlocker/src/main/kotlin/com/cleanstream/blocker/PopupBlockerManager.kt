package com.cleanstream.blocker

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.text.SimpleDateFormat
import java.util.Date
import java.util.HashSet
import java.util.Locale
import java.util.Map

object PopupBlockerManager {

    private const val TAG = "PopupBlocker"
    private const val PREFS_NAME = "popup_blocker_shield_prefs"
    const val KEY_BLOCK_DONATION_DIALOGS = "block_donation_dialogs"
    const val KEY_IMMUNIZE_PREFS = "immunize_prefs"
    const val KEY_AUTO_DISMISS_ADS = "auto_dismiss_ads"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scannedLoaders = HashSet<ClassLoader>()
    private var appContext: Context? = null
    private var currentActivityRef: WeakReference<Activity>? = null
    private var isLifecycleRegistered = false
    private var guardianJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isBlockDonationDialogsEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_BLOCK_DONATION_DIALOGS, true)
    }

    fun setBlockDonationDialogsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_BLOCK_DONATION_DIALOGS, enabled).apply()
        if (enabled) {
            immunizePreferences(context)
            scanAndNeutralize()
        }
    }

    fun isImmunizePrefsEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_IMMUNIZE_PREFS, true)
    }

    fun setImmunizePrefsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_IMMUNIZE_PREFS, enabled).apply()
        if (enabled) {
            immunizePreferences(context)
        }
    }

    fun isAutoDismissAdsEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTO_DISMISS_ADS, true)
    }

    fun setAutoDismissAdsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_DISMISS_ADS, enabled).apply()
    }

    fun init(context: Context) {
        val app = (context.applicationContext as? Application) ?: (context as? Application)
        appContext = app ?: context

        Log.i(TAG, "Initializing PopupBlockerManager guardian shield")

        // 1. Proactively immunize SharedPreferences so DonationManager check returns immediately
        immunizePreferences(context)

        // 2. Hook current activity if already active
        if (context is FragmentActivity) {
            hookActivity(context)
        }

        // 3. Register global activity lifecycle callbacks
        if (app != null && !isLifecycleRegistered) {
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    currentActivityRef = WeakReference(activity)
                    if (activity is FragmentActivity) {
                        hookActivity(activity)
                    }
                    immunizePreferences(activity)
                    scanAndNeutralize()
                }

                override fun onActivityStarted(activity: Activity) {
                    currentActivityRef = WeakReference(activity)
                    if (activity is FragmentActivity) {
                        dismissPendingDialogs(activity.supportFragmentManager)
                    }
                }

                override fun onActivityResumed(activity: Activity) {
                    currentActivityRef = WeakReference(activity)
                    if (activity is FragmentActivity) {
                        hookActivity(activity)
                        dismissPendingDialogs(activity.supportFragmentManager)
                    }
                    immunizePreferences(activity)
                    scanAndNeutralize()
                }

                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {
                    if (currentActivityRef?.get() == activity) {
                        currentActivityRef = null
                    }
                }
            })
            isLifecycleRegistered = true
        }

        // 4. Start periodic background immunization loop
        startGuardianDaemon()

        // 5. Reflection scan of loaded plugins
        scanAndNeutralize()
    }

    fun cleanup() {
        guardianJob?.cancel()
        guardianJob = null
        scannedLoaders.clear()
        Log.i(TAG, "PopupBlockerManager cleaned up")
    }

    private fun hookActivity(activity: FragmentActivity) {
        try {
            val fm = activity.supportFragmentManager
            dismissPendingDialogs(fm)

            fm.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentAttached(fm: FragmentManager, f: Fragment, context: Context) {
                    super.onFragmentAttached(fm, f, context)
                    checkAndDismiss(f)
                }

                override fun onFragmentCreated(fm: FragmentManager, f: Fragment, savedInstanceState: Bundle?) {
                    super.onFragmentCreated(fm, f, savedInstanceState)
                    checkAndDismiss(f)
                }

                override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
                    super.onFragmentViewCreated(fm, f, v, savedInstanceState)
                    checkAndDismiss(f)
                    inspectViewForAds(f, v)
                }

                override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
                    super.onFragmentStarted(fm, f)
                    checkAndDismiss(f)
                }

                override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                    super.onFragmentResumed(fm, f)
                    checkAndDismiss(f)
                }
            }, true)
        } catch (t: Throwable) {
            Log.e(TAG, "Error hooking activity fragment manager", t)
        }
    }

    private fun checkAndDismiss(f: Fragment?) {
        if (f == null) return
        val ctx = appContext ?: return
        if (!isBlockDonationDialogsEnabled(ctx)) return

        val tag = f.tag
        val className = f.javaClass.name
        var isTarget = false

        if (tag != null) {
            val lowerTag = tag.lowercase(Locale.US)
            if (lowerTag == "donation_dialog" ||
                lowerTag.contains("donation") ||
                lowerTag.contains("support_repo") ||
                lowerTag.contains("ad_dialog")
            ) {
                isTarget = true
            }
        }

        if (!isTarget) {
            val lowerClass = className.lowercase(Locale.US)
            if (lowerClass.contains("donationdialogfragment") ||
                (lowerClass.contains("cncverse") && lowerClass.contains("donation"))
            ) {
                isTarget = true
            }
        }

        if (isTarget) {
            Log.i(TAG, "Shield active: blocked popup fragment [tag=$tag, class=$className]")
            silentDismiss(f)
        }
    }

    private fun inspectViewForAds(f: Fragment, view: View?) {
        val ctx = appContext ?: return
        if (!isAutoDismissAdsEnabled(ctx)) return
        if (view == null) return

        try {
            if (containsAdKeywords(view)) {
                Log.i(TAG, "Shield active: blocked popup matching donation/ad banner keywords")
                silentDismiss(f)
            }
        } catch (_: Throwable) {}
    }

    private fun containsAdKeywords(v: View): Boolean {
        if (v is TextView) {
            val text = v.text?.toString()?.lowercase(Locale.US) ?: ""
            if (text.contains("keep cncverse alive") ||
                text.contains("support the repo") ||
                text.contains("watch an ad to support") ||
                text.contains("cncverse stays free") ||
                text.contains("awesome supporters")
            ) {
                return true
            }
        } else if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                if (containsAdKeywords(v.getChildAt(i))) {
                    return true
                }
            }
        }
        return false
    }

    private fun silentDismiss(f: Fragment) {
        mainHandler.post {
            try {
                if (f is DialogFragment) {
                    val dialog = f.dialog
                    if (dialog != null) {
                        dialog.window?.decorView?.visibility = View.GONE
                        dialog.dismiss()
                    }
                    f.dismissAllowingStateLoss()
                }
                if (f.isAdded && f.parentFragmentManager != null) {
                    f.parentFragmentManager.beginTransaction().remove(f).commitAllowingStateLoss()
                }
            } catch (_: Throwable) {}
        }
    }

    private fun dismissPendingDialogs(fm: FragmentManager?) {
        if (fm == null) return
        try {
            val f = fm.findFragmentByTag("donation_dialog")
            if (f != null) {
                silentDismiss(f)
            }
        } catch (_: Throwable) {}
    }

    fun immunizePreferences(context: Context?) {
        if (context == null) return
        if (!isImmunizePrefsEnabled(context)) return

        try {
            val sdf = SimpleDateFormat("yyyyMMdd", Locale.US)
            val today = sdf.format(Date())

            val prefs = context.getSharedPreferences("cncverse_donation", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("last_shown_day", today)
                .putString("cncverse_donation_achieved_shown_month", "Goal Reached")
                .putFloat("cncverse_donation_cached_amount", 1000.0f)
                .putFloat("cncverse_donation_cached_goal", 100.0f)
                .putInt("cncverse_donation_cached_percent", 100)
                .putInt("cncverse_donation_cached_supporters", 999)
                .putString("cncverse_donation_cached_month", "Goal Reached")
                .apply()
        } catch (_: Throwable) {}
    }

    fun scanAndNeutralize() {
        mainHandler.post {
            try {
                neutralizeClassLoader(Thread.currentThread().contextClassLoader)
                neutralizeClassLoader(PopupBlockerManager::class.java.classLoader)

                // Try PluginManager.plugins via reflection
                try {
                    val pmClass = Class.forName("com.lagradost.cloudstream3.plugins.PluginManager")
                    val pluginsField: Field = pmClass.getDeclaredField("plugins")
                    pluginsField.isAccessible = true
                    val mapObj = pluginsField.get(null)
                    if (mapObj is Map<*, *>) {
                        for (pluginInstance in mapObj.values) {
                            if (pluginInstance != null) {
                                neutralizeClassLoader(pluginInstance.javaClass.classLoader)
                            }
                        }
                    }
                } catch (_: Throwable) {}
            } catch (_: Throwable) {}
        }
    }

    private fun neutralizeClassLoader(cl: ClassLoader?) {
        if (cl == null || !scannedLoaders.add(cl)) return

        try {
            val dmClass = Class.forName("com.cncverse.donation.DonationManager", false, cl)
            Log.i(TAG, "Found DonationManager in ClassLoader [$cl], neutralizing triggers")

            // Future trigger timestamp -> (now - lastTriggerMs < GATE_WINDOW_MS) evaluates true
            try {
                val f1 = dmClass.getDeclaredField("lastTriggerMs")
                f1.isAccessible = true
                f1.setLong(null, System.currentTimeMillis() + 86400000000L)
            } catch (_: Throwable) {}

            // Infinite cooldown hours
            try {
                val f2 = dmClass.getDeclaredField("cooldownHours")
                f2.isAccessible = true
                f2.setInt(null, 999999)
            } catch (_: Throwable) {}

            // Disable testMode
            try {
                val f3 = dmClass.getDeclaredField("testMode")
                f3.isAccessible = true
                f3.setBoolean(null, false)
            } catch (_: Throwable) {}
        } catch (_: ClassNotFoundException) {
            // Not in this classloader
        } catch (t: Throwable) {
            Log.w(TAG, "Error neutralizing DonationManager: ${t.message}")
        }
    }

    private fun startGuardianDaemon() {
        if (guardianJob?.isActive == true) return

        guardianJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(10 * 60 * 1000L) // Every 10 minutes
                val ctx = appContext ?: continue
                immunizePreferences(ctx)
                scanAndNeutralize()
            }
        }
    }
}
