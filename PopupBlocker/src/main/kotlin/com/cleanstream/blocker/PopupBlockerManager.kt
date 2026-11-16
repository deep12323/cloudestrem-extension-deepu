package com.cleanstream.blocker

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
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
import java.util.Calendar
import java.util.Date
import java.util.Locale

object PopupBlockerManager {

    private const val TAG = "PopupBlocker"
    private const val PREFS_NAME = "popup_blocker_shield_prefs"
    const val KEY_BLOCK_DONATION_DIALOGS = "block_donation_dialogs"
    const val KEY_IMMUNIZE_PREFS = "immunize_prefs"
    const val KEY_AUTO_DISMISS_ADS = "auto_dismiss_ads"

    private val KNOWN_DONATION_CLASSES = listOf(
        "com.allforu.Announcer",
        "com.allforu.AnnouncerCard",
        "com.cncverse.donation.DonationManager",
        "com.raghav.donation.DonationManager",
        "com.netnaija.app.donation.DonationManager",
        "com.netnaija.donation.DonationManager",
        "com.phisher98.donation.DonationManager",
        "com.cncverse.donation.DonationConfig",
        "com.phisher98.donation.DonationConfig"
    )

    private val KNOWN_DIALOG_TAGS = listOf(
        "donation_dialog",
        "raghav_donation_dialog",
        "phisher_donation_floating_dialog"
    )

    val AD_KEYWORDS = listOf(
        "support allforu",
        "allforu",
        "monthly support goal",
        "allforu-goal.pages.dev",
        "allforu-goal",
        "buymeacoffee.com/allforu1",
        "buymeacoffee.com",
        "rzp.io",
        "choose your donation option",
        "community-funded, always free",
        "keep cncverse alive",
        "support the repo",
        "watch an ad to support",
        "cncverse stays free",
        "awesome supporters",
        "kshitij8473.github.io/donate",
        "donate via upi",
        "help keep phisher repo alive",
        "support phisher repo",
        "goal missed = slower updates",
        "support us to keep this extension maintained",
        "recent contribution",
        "can't donate? watch an ad",
        "phisher98",
        "raghav766",
        "maybe later (",
        "rvr sky angel",
        "t.me/+lt1xwtmgyirhmzc9"
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scannedLoaders = HashSet<ClassLoader>()
    private var appContext: Context? = null
    private var currentActivityRef: WeakReference<Activity>? = null
    private var isLifecycleRegistered = false
    private var isWmgHooked = false
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
            currentActivityRef?.get()?.let { act ->
                sweepWindowViews()
                dismissAnnouncerCurrent()
                if (act is FragmentActivity) {
                    dismissPendingDialogs(act.supportFragmentManager)
                }
            }
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
        var app: Application? = null
        var cur: Context? = context
        while (cur != null) {
            if (cur is Application) {
                app = cur
                break
            }
            if (cur is ContextWrapper) {
                cur = cur.baseContext
            } else {
                break
            }
        }
        if (app == null) {
            app = context.applicationContext as? Application
        }
        appContext = app ?: context

        Log.i(TAG, "Initializing PopupBlockerManager universal shield across all extensions")

        // 1. Proactively immunize SharedPreferences for CNCVerse, Raghav, Phisher, NetNaija, etc.
        immunizePreferences(context)

        // 2. Discover CommonActivity if available
        try {
            val commonActClass = Class.forName("com.lagradost.cloudstream3.CommonActivity")
            val actField = commonActClass.getDeclaredField("activity")
            actField.isAccessible = true
            val act = actField.get(null) as? Activity
            if (act != null) {
                currentActivityRef = WeakReference(act)
                hookActivity(act)
                if (app == null) {
                    app = act.application
                    if (appContext !is Application && app != null) {
                        appContext = app
                    }
                }
            }
        } catch (_: Throwable) {}

        // Hook passed activity if already active
        if (context is Activity) {
            hookActivity(context)
        }

        // 3. Register global activity lifecycle callbacks
        if (app != null && !isLifecycleRegistered) {
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    currentActivityRef = WeakReference(activity)
                    hookActivity(activity)
                    immunizePreferences(activity)
                    scanAndNeutralize()
                    sweepWindowViews()
                    dismissAnnouncerCurrent()
                }

                override fun onActivityStarted(activity: Activity) {
                    currentActivityRef = WeakReference(activity)
                    hookActivity(activity)
                    sweepWindowViews()
                    dismissAnnouncerCurrent()
                    if (activity is FragmentActivity) {
                        dismissPendingDialogs(activity.supportFragmentManager)
                    }
                }

                override fun onActivityResumed(activity: Activity) {
                    currentActivityRef = WeakReference(activity)
                    hookActivity(activity)
                    sweepWindowViews()
                    dismissAnnouncerCurrent()
                    if (activity is FragmentActivity) {
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

        // 4. Hook WindowManagerGlobal to intercept direct native AlertDialogs instantly
        hookWindowManager()

        // 5. Start fast guardian daemon loop (every 500ms when active)
        startGuardianDaemon()

        // 6. Deep reflection scan of loaded plugins
        scanAndNeutralize()
    }

    fun cleanup() {
        guardianJob?.cancel()
        guardianJob = null
        scannedLoaders.clear()
        Log.i(TAG, "PopupBlockerManager cleaned up")
    }

    private fun hookActivity(activity: Activity) {
        try {
            currentActivityRef = WeakReference(activity)

            // 1. Hook FragmentManager if FragmentActivity
            if (activity is FragmentActivity) {
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
            }

            // 2. Hook Window Callback on the Activity to intercept direct AlertDialogs when focus changes
            val win = activity.window
            val currentCallback = win.callback
            if (currentCallback != null && currentCallback !is ShieldWindowCallback) {
                win.callback = ShieldWindowCallback(currentCallback)
            }

            // 3. Attach layout listener to decor view to catch dynamic dialog appearances
            win.decorView.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    sweepWindowViews()
                    dismissAnnouncerCurrent()
                }
            })

            // 4. Ensure WindowManager hook is installed
            hookWindowManager()

            // 5. Immediate window sweep
            sweepWindowViews()
            dismissAnnouncerCurrent()
        } catch (t: Throwable) {
            Log.e(TAG, "Error hooking activity: ${t.message}", t)
        }
    }

    private class ShieldWindowCallback(private val delegate: Window.Callback) : Window.Callback by delegate {
        override fun onWindowFocusChanged(hasFocus: Boolean) {
            delegate.onWindowFocusChanged(hasFocus)
            if (!hasFocus) {
                sweepWindowViews()
                dismissAnnouncerCurrent()
            }
        }

        override fun onAttachedToWindow() {
            delegate.onAttachedToWindow()
            sweepWindowViews()
            dismissAnnouncerCurrent()
        }

        override fun onContentChanged() {
            delegate.onContentChanged()
            sweepWindowViews()
            dismissAnnouncerCurrent()
        }
    }

    private fun hookWindowManager() {
        if (isWmgHooked) return
        mainHandler.post {
            try {
                val wmgClass = Class.forName("android.view.WindowManagerGlobal")
                val getInstanceMethod = wmgClass.getMethod("getInstance")
                val wmg = getInstanceMethod.invoke(null) ?: return@post
                val mViewsField = wmgClass.getDeclaredField("mViews")
                mViewsField.isAccessible = true
                val currentList = mViewsField.get(wmg) as? ArrayList<View> ?: return@post

                if (currentList !is ShieldViewsList) {
                    val shieldList = ShieldViewsList(currentList)
                    mViewsField.set(wmg, shieldList)
                    isWmgHooked = true
                    Log.i(TAG, "Shield active: WindowManagerGlobal mViews hook installed")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "WindowManagerGlobal hook fallback: ${t.message}")
            }
        }
    }

    private class ShieldViewsList(val original: ArrayList<View>) : ArrayList<View>(original) {
        override fun add(element: View): Boolean {
            checkElement(element)
            return super.add(element)
        }

        override fun add(index: Int, element: View) {
            checkElement(element)
            super.add(index, element)
        }

        private fun checkElement(v: View) {
            try {
                if (isDonationWindowView(v)) {
                    v.visibility = View.GONE
                    v.alpha = 0f
                    postImmediateNeutralize(v)
                }
            } catch (_: Throwable) {}
        }
    }

    fun postImmediateNeutralize(v: View) {
        mainHandler.post {
            try {
                val wmgClass = Class.forName("android.view.WindowManagerGlobal")
                val getInstanceMethod = wmgClass.getMethod("getInstance")
                val wmg = getInstanceMethod.invoke(null) ?: return@post
                neutralizeWindowView(wmg, v)
            } catch (_: Throwable) {
                dismissAnnouncerCurrent()
            }
        }
    }

    fun isDonationWindowView(v: View): Boolean {
        val className = v.javaClass.name.lowercase(Locale.US)
        if (className.contains("announcercard") ||
            className.contains("donationcard") ||
            className.contains("donationdialog") ||
            className.contains(".donation.")
        ) {
            return true
        }
        return inspectViewForDonationKeywords(v)
    }

    private fun inspectViewForDonationKeywords(v: View): Boolean {
        val className = v.javaClass.name.lowercase(Locale.US)
        if (className.contains("announcercard") || className.contains("donationcard")) {
            return true
        }

        if (v is TextView) {
            val text = v.text?.toString()?.lowercase(Locale.US) ?: ""
            if (text.isNotEmpty()) {
                for (kw in AD_KEYWORDS) {
                    if (text.contains(kw)) return true
                }
                if (text.contains("monthly goal") &&
                    (text.contains("target") || text.contains("raised") || text.contains("donor") || text.contains("supporter"))
                ) {
                    return true
                }
            }
        } else if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                if (inspectViewForDonationKeywords(v.getChildAt(i))) {
                    return true
                }
            }
        }
        return false
    }

    fun sweepWindowViews() {
        mainHandler.post {
            try {
                val ctx = appContext ?: return@post
                if (!isBlockDonationDialogsEnabled(ctx)) return@post

                val wmgClass = Class.forName("android.view.WindowManagerGlobal")
                val getInstance = wmgClass.getMethod("getInstance")
                val wmg = getInstance.invoke(null) ?: return@post
                val mViewsField = wmgClass.getDeclaredField("mViews")
                mViewsField.isAccessible = true
                val views = mViewsField.get(wmg) as? List<*> ?: return@post

                val act = currentActivityRef?.get()
                val actDecorView = act?.window?.decorView

                for (item in ArrayList(views)) {
                    val v = item as? View ?: continue
                    if (actDecorView != null && v == actDecorView) continue

                    if (isDonationWindowView(v)) {
                        Log.i(TAG, "Shield active: swept and neutralizing donation window view: $v")
                        neutralizeWindowView(wmg, v)
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    private fun neutralizeWindowView(wmg: Any, v: View) {
        try {
            // 1. Hide immediately to eliminate any visual flicker
            v.visibility = View.GONE
            v.alpha = 0f

            // 2. Dismiss Announcer.current if loaded
            dismissAnnouncerCurrent()

            // 3. Try to click close / maybe later button
            if (!clickCloseOrCancelButton(v)) {
                // 4. Dispatch back key event to dialog window
                v.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
                v.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
            }

            // 5. Try removeView from WindowManagerGlobal as final fallback
            mainHandler.postDelayed({
                try {
                    if (v.isAttachedToWindow) {
                        val removeViewMethod = wmg.javaClass.getMethod("removeView", View::class.java, java.lang.Boolean.TYPE)
                        removeViewMethod.invoke(wmg, v, false)
                    }
                } catch (_: Throwable) {}
            }, 50L)
        } catch (_: Throwable) {}
    }

    private fun clickCloseOrCancelButton(v: View): Boolean {
        if (v.isClickable) {
            if (v is TextView) {
                val text = v.text?.toString()?.trim()?.lowercase(Locale.US) ?: ""
                if (text == "✕" || text == "x" || text == "close" || text == "maybe later" || text == "cancel" || text == "dismiss") {
                    v.performClick()
                    return true
                }
            }
            val desc = v.contentDescription?.toString()?.trim()?.lowercase(Locale.US) ?: ""
            if (desc == "close" || desc == "cancel" || desc == "dismiss") {
                v.performClick()
                return true
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                if (clickCloseOrCancelButton(v.getChildAt(i))) {
                    return true
                }
            }
        }
        return false
    }

    fun dismissAnnouncerCurrent() {
        mainHandler.post {
            for (cl in scannedLoaders) {
                try {
                    val clazz = Class.forName("com.allforu.Announcer", false, cl)
                    val currentField = clazz.getDeclaredField("current")
                    currentField.isAccessible = true
                    val currentDialog = currentField.get(null)
                    if (currentDialog != null) {
                        if (currentDialog is Dialog) {
                            currentDialog.dismiss()
                        }
                        currentField.set(null, null)
                        Log.i(TAG, "Shield active: dismissed Announcer.current dialog")
                    }

                    val animField = clazz.getDeclaredField("anim")
                    animField.isAccessible = true
                    val anim = animField.get(null)
                    if (anim is android.animation.ValueAnimator) {
                        anim.cancel()
                        animField.set(null, null)
                    }

                    // Keep appCtx nullified so showOnHome immediately aborts
                    val appCtxField = clazz.getDeclaredField("appCtx")
                    appCtxField.isAccessible = true
                    appCtxField.set(null, null)
                } catch (_: Throwable) {}
            }
        }
    }

    private fun checkAndDismiss(f: Fragment?) {
        if (f == null) return
        val ctx = appContext ?: return
        if (!isBlockDonationDialogsEnabled(ctx)) return

        val tag = f.tag?.lowercase(Locale.US) ?: ""
        val className = f.javaClass.name.lowercase(Locale.US)
        var isTarget = false

        if (tag.isNotEmpty()) {
            if (KNOWN_DIALOG_TAGS.contains(tag) ||
                tag.contains("donation") ||
                tag.contains("donate") ||
                tag.contains("support_repo") ||
                tag.contains("ad_dialog") ||
                tag.contains("sponsor") ||
                tag.contains("support_dialog")
            ) {
                isTarget = true
            }
        }

        if (!isTarget) {
            if (className.contains(".donation.") ||
                className.contains("donationdialogfragment") ||
                className.contains("donationdialog") ||
                className.contains("donatedialog") ||
                className.contains("sponsordialog") ||
                className.contains("supportrepo")
            ) {
                isTarget = true
            }
        }

        if (isTarget) {
            Log.i(TAG, "Shield active: blocked popup fragment [tag=$tag, class=${f.javaClass.name}]")
            silentDismiss(f)
        }
    }

    private fun inspectViewForAds(f: Fragment, view: View?) {
        val ctx = appContext ?: return
        if (!isAutoDismissAdsEnabled(ctx)) return
        if (view == null) return

        try {
            if (inspectViewForDonationKeywords(view)) {
                Log.i(TAG, "Shield active: blocked popup matching donation/ad banner keywords in [${f.javaClass.name}]")
                silentDismiss(f)
            }
        } catch (_: Throwable) {}
    }

    private fun silentDismiss(f: Fragment) {
        mainHandler.post {
            try {
                if (f is DialogFragment) {
                    val dialog = f.dialog
                    if (dialog != null) {
                        dialog.window?.decorView?.apply {
                            visibility = View.GONE
                            alpha = 0f
                        }
                        dialog.dismiss()
                    }
                    f.dismissAllowingStateLoss()
                }
                if (f.isAdded) {
                    try {
                        f.parentFragmentManager.beginTransaction().remove(f).commitAllowingStateLoss()
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }
    }

    private fun dismissPendingDialogs(fm: FragmentManager?) {
        if (fm == null) return
        try {
            for (tag in KNOWN_DIALOG_TAGS) {
                val f = fm.findFragmentByTag(tag)
                if (f != null) {
                    silentDismiss(f)
                }
            }

            // Check all active fragments
            for (f in ArrayList(fm.fragments)) {
                if (f != null) {
                    checkAndDismiss(f)
                }
            }
        } catch (_: Throwable) {}
    }

    fun immunizePreferences(context: Context?) {
        if (context == null) return
        if (!isImmunizePrefsEnabled(context)) return

        try {
            val sdf = SimpleDateFormat("yyyyMMdd", Locale.US)
            val today = sdf.format(Date())
            val nowMs = System.currentTimeMillis()

            val cal = Calendar.getInstance()
            val monthKey = String.format(
                Locale.US,
                "%04d-%02d",
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1
            )

            // 1. CNCVerse Immunization
            val cncPrefs = context.getSharedPreferences("cncverse_donation", Context.MODE_PRIVATE)
            cncPrefs.edit()
                .putString("last_shown_day", today)
                .putString("cncverse_donation_achieved_shown_month", "Goal Reached")
                .putFloat("cncverse_donation_cached_amount", 10000.0f)
                .putFloat("cncverse_donation_cached_goal", 100.0f)
                .putInt("cncverse_donation_cached_percent", 100)
                .putInt("cncverse_donation_cached_supporters", 9999)
                .putString("cncverse_donation_cached_month", "Goal Reached")
                .putLong("lastTriggerMs", nowMs + 86400000000L)
                .apply()

            // 2. Raghav Repo Immunization (VegaMovies, Senshi, TheMoviesFlix, etc.)
            val raghavGoalJson = "{\"month\":\"$monthKey\",\"targetAmount\":1.0,\"currentAmount\":99999.0,\"currency\":\"$\",\"lastDonor\":\"Anonymous\",\"lastDonation\":\"$100\"}"
            val raghavPrefs = context.getSharedPreferences("raghav_donation_prefs", Context.MODE_PRIVATE)
            raghavPrefs.edit()
                .putLong("raghav_donation_last_shown", nowMs)
                .putString("raghav_donation_goal_cache", raghavGoalJson)
                .apply()

            // 3. NetNaija Immunization
            val netnaijaPrefs = context.getSharedPreferences("netnaija_donation_prefs", Context.MODE_PRIVATE)
            netnaijaPrefs.edit()
                .putLong("netnaija_donation_last_shown", nowMs)
                .putLong("donation_last_shown", nowMs)
                .apply()

            // 4. Phisher Repo Immunization (AllMovieLand, AllWish, Anichi, etc.)
            val phisherPrefs = context.getSharedPreferences("phisher_donation_prefs", Context.MODE_PRIVATE)
            phisherPrefs.edit()
                .putLong("phisher_donation_last_shown_v2", nowMs)
                .putString("phisher_donation_cached_month_v3", "Goal Reached")
                .putFloat("phisher_donation_cached_amount_v3", 10000.0f)
                .putInt("phisher_donation_cached_supporters_v3", 9999)
                .apply()

            // 5. Generic Fallback Immunization
            for (genericName in listOf("donation_prefs", "donation_preferences", "repo_donation_prefs")) {
                val gPrefs = context.getSharedPreferences(genericName, Context.MODE_PRIVATE)
                gPrefs.edit()
                    .putLong("last_shown", nowMs)
                    .putLong("last_shown_time", nowMs)
                    .putString("last_shown_day", today)
                    .putBoolean("is_cooldown_active", true)
                    .putBoolean("goal_achieved", true)
                    .apply()
            }
        } catch (_: Throwable) {}
    }

    fun scanAndNeutralize() {
        mainHandler.post {
            try {
                neutralizeClassLoader(Thread.currentThread().contextClassLoader)
                neutralizeClassLoader(PopupBlockerManager::class.java.classLoader)

                // Try PluginManager.plugins via reflection to find all loaded plugin class loaders
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

        for (className in KNOWN_DONATION_CLASSES) {
            try {
                val clazz = Class.forName(className, false, cl)
                neutralizeClass(clazz)
                Log.i(TAG, "Successfully neutralized donation class: $className")
            } catch (_: ClassNotFoundException) {
                // Not in this ClassLoader
            } catch (t: Throwable) {
                Log.w(TAG, "Error neutralizing class $className: ${t.message}")
            }
        }
    }

    private fun neutralizeClass(clazz: Class<*>) {
        // Specific neutralization for AllForU Announcer
        if (clazz.name.contains("Announcer")) {
            try {
                val currentField = try { clazz.getDeclaredField("current") } catch (_: Throwable) { null }
                if (currentField != null) {
                    currentField.isAccessible = true
                    val currentDialog = currentField.get(null)
                    if (currentDialog is Dialog) {
                        currentDialog.dismiss()
                        currentField.set(null, null)
                        Log.i(TAG, "Dismissed active Announcer.current dialog")
                    }
                }

                val animField = try { clazz.getDeclaredField("anim") } catch (_: Throwable) { null }
                if (animField != null) {
                    animField.isAccessible = true
                    val animObj = animField.get(null)
                    if (animObj is android.animation.ValueAnimator) {
                        animObj.cancel()
                        animField.set(null, null)
                    }
                }

                val appCtxField = try { clazz.getDeclaredField("appCtx") } catch (_: Throwable) { null }
                if (appCtxField != null) {
                    appCtxField.isAccessible = true
                    appCtxField.set(null, null)
                }

                val initedField = try { clazz.getDeclaredField("inited") } catch (_: Throwable) { null }
                if (initedField != null) {
                    initedField.isAccessible = true
                    initedField.setBoolean(null, true)
                }

                val seqField = try { clazz.getDeclaredField("requestSeq") } catch (_: Throwable) { null }
                if (seqField != null) {
                    seqField.isAccessible = true
                    seqField.setInt(null, Int.MIN_VALUE)
                }

                val goalCacheAtField = try { clazz.getDeclaredField("goalCacheAt") } catch (_: Throwable) { null }
                if (goalCacheAtField != null) {
                    goalCacheAtField.isAccessible = true
                    goalCacheAtField.setLong(null, Long.MAX_VALUE)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Error in specific Announcer neutralization: ${t.message}")
            }
        }

        // Generic neutralization for donation manager fields
        for (field in clazz.declaredFields) {
            try {
                field.isAccessible = true
                val name = field.name.lowercase(Locale.US)
                val type = field.type

                when {
                    name.contains("isdialogshowing") || name.contains("dialogshowing") -> {
                        if (type == java.lang.Boolean.TYPE) field.setBoolean(null, true)
                    }
                    name.contains("islaunching") || name.contains("launching") -> {
                        if (type == java.lang.Boolean.TYPE) field.setBoolean(null, true)
                    }
                    name.contains("testmode") -> {
                        if (type == java.lang.Boolean.TYPE) field.setBoolean(null, false)
                    }
                    name.contains("cooldownhours") -> {
                        if (type == java.lang.Integer.TYPE) field.setInt(null, 999999)
                        else if (type == java.lang.Long.TYPE) field.setLong(null, 999999L)
                    }
                    name.contains("cooldownmillis") || name.contains("cooldown") -> {
                        if (type == java.lang.Integer.TYPE) field.setInt(null, 999999)
                        else if (type == java.lang.Long.TYPE) field.setLong(null, 86400000000000L)
                    }
                    name.contains("lasttrigger") || name.contains("lastshown") -> {
                        if (type == java.lang.Long.TYPE) field.setLong(null, System.currentTimeMillis() + 86400000000L)
                    }
                    name == "enabled" -> {
                        if (type == java.lang.Boolean.TYPE) field.setBoolean(null, false)
                    }
                    name.contains("goalachieved") || name.contains("isgoalachieved") -> {
                        if (type == java.lang.Boolean.TYPE) field.setBoolean(null, true)
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    private fun startGuardianDaemon() {
        if (guardianJob?.isActive == true) return

        guardianJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val act = currentActivityRef?.get()
                val isForeground = act != null && !act.isFinishing && !act.isDestroyed
                val delayMs = if (isForeground) 500L else 10000L
                delay(delayMs)

                val ctx = appContext ?: continue
                immunizePreferences(ctx)
                scanAndNeutralize()

                if (isForeground) {
                    mainHandler.post {
                        sweepWindowViews()
                        dismissAnnouncerCurrent()
                        if (act is FragmentActivity) {
                            dismissPendingDialogs(act.supportFragmentManager)
                        }
                    }
                }
            }
        }
    }
}
