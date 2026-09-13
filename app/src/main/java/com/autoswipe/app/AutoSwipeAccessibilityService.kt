package com.autoswipe.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AutoSwipeAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AutoSwipeAccessibilityService? = null

        private const val TAG = "AutoSwipeEvents"

        const val PREFS_NAME = "autoswipe_prefs"
        const val PREF_AUTO_SWIPE_ENABLED = "auto_swipe_enabled"
        const val PREF_TARGET_PACKAGE = "target_package"
        const val PREF_NEAR_END_RATIO = "near_end_ratio"
        const val PREF_RESET_RATIO = "reset_ratio"
        const val PREF_SWIPE_COOLDOWN_MS = "swipe_cooldown_ms"

        // Comma-separated list — ek se zyada app ek saath watch ho sakte hain
        const val DEFAULT_TARGET_PACKAGE = "com.google.android.youtube,com.instagram.android"

        // Progress ratio (0.0 - 1.0) jispar hum "near end" maanenge
        const val DEFAULT_NEAR_END_RATIO = 0.92f

        // Loop ke baad kitna neeche gira ho tab "reset" (naya loop) maanenge
        const val DEFAULT_RESET_RATIO = 0.15f

        // Ek short ke liye sirf ek hi baar swipe trigger ho — isliye cooldown
        const val DEFAULT_SWIPE_COOLDOWN_MS = 1200L

        // Manual scrub (user khud seekbar ko end se start tak drag kare) vs
        // real autoplay loop ke beech farak karne ke liye:
        // Real loop mein progress end se start tak EK hi event mein achanak
        // gir jaata hai. Manual drag mein ye gradually multiple events mein
        // hota hai (chota-chota drop) ya zyada time leta hai.
        //
        // Drop, near-end/reset ke configured gap ka kam se kam itna fraction
        // hona chahiye taaki "sudden jump" mana jaaye
        private const val MIN_LOOP_DROP_FRACTION = 0.6f

        // Near-end dekhne se lekar reset dikhne tak itne ms se zyada time
        // laga to ye manual scrub jaisa maana jaayega, real loop nahi
        private const val MAX_LOOP_TRANSITION_MS = 800L

        // YouTube Shorts player smooth playback ke dauraan accessibility
        // events fire hi nahi karta (sirf initial load par) — loop hone par
        // bhi nahi. Isliye events ka wait karne ki jagah, khud active poll
        // karte hain taaki progress miss na ho.
        //
        // Battery bachane ke liye adaptive polling: jab tak video near-end
        // range mein nahi pahuncha, dheeme se poll karo (loop precisely
        // catch karne ki zaroorat nahi hai abhi). Near-end range mein aate
        // hi tez poll karo taaki loop ka exact moment miss na ho
        private const val FAST_POLL_INTERVAL_MS = 200L
        private const val SLOW_POLL_INTERVAL_MS = 750L

        // Instagram jaise apps mein kuch ads (product carousel jaisi sponsored
        // posts) ka koi progress/seekbar hi nahi hota, isliye wo kabhi khud
        // "khatam" nahi hongi. Instagram unhe explicitly "Ad" text/description
        // se label karta hai — wahi dhoondh ke itne ms baad hum khud swipe kar
        // dete hain
        private const val AD_SKIP_DELAY_MS = 1500L

        // YouTube SeekBar apna rangeInfo set nahi karta — sirf ek human-readable
        // contentDescription deta hai jaise "0 minutes 51 seconds of 1 minute 6
        // seconds". Isse elapsed/total seconds nikalne ke liye regex
        private val PROGRESS_DESCRIPTION_REGEX = Regex(
            """(\d+)\s*minutes?\s*(\d+)\s*seconds?\s*of\s*(\d+)\s*minutes?\s*(\d+)\s*seconds?""",
            RegexOption.IGNORE_CASE
        )

        /**
         * "X minutes Y seconds of A minutes B seconds" jaisa text parse karke
         * (elapsedSeconds, totalSeconds) return karta hai, ya match na hone par null.
         */
        private fun parseElapsedAndTotalSeconds(description: CharSequence?): Pair<Float, Float>? {
            val text = description?.toString() ?: return null
            val match = PROGRESS_DESCRIPTION_REGEX.find(text) ?: return null

            val (curMin, curSec, totalMin, totalSec) = match.destructured

            val elapsedSeconds = curMin.toFloat() * 60 + curSec.toFloat()
            val totalSeconds = totalMin.toFloat() * 60 + totalSec.toFloat()

            return elapsedSeconds to totalSeconds
        }

        fun isAutoSwipeEnabled(context: Context): Boolean {
            return prefs(context).getBoolean(PREF_AUTO_SWIPE_ENABLED, true)
        }

        fun setAutoSwipeEnabled(context: Context, enabled: Boolean) {
            prefs(context).edit().putBoolean(PREF_AUTO_SWIPE_ENABLED, enabled).apply()
        }

        fun getTargetPackage(context: Context): String {
            return prefs(context).getString(PREF_TARGET_PACKAGE, DEFAULT_TARGET_PACKAGE)
                ?: DEFAULT_TARGET_PACKAGE
        }

        fun setTargetPackage(context: Context, packageName: String) {
            prefs(context).edit().putString(PREF_TARGET_PACKAGE, packageName).apply()
        }

        /**
         * Target package setting comma-separated multiple apps rakh sakti hai
         * (jaise YouTube + Instagram dono ek saath). Ye check karta hai ki
         * di gayi package un mein se koi hai ya nahi.
         */
        fun isWatchedPackage(context: Context, packageName: String?): Boolean {
            if (packageName == null) return false
            return getTargetPackage(context)
                .split(",")
                .map { it.trim() }
                .any { it.isNotEmpty() && it == packageName }
        }

        fun getNearEndRatio(context: Context): Float {
            return prefs(context).getFloat(PREF_NEAR_END_RATIO, DEFAULT_NEAR_END_RATIO)
        }

        fun setNearEndRatio(context: Context, ratio: Float) {
            prefs(context).edit().putFloat(PREF_NEAR_END_RATIO, ratio).apply()
        }

        fun getResetRatio(context: Context): Float {
            return prefs(context).getFloat(PREF_RESET_RATIO, DEFAULT_RESET_RATIO)
        }

        fun setResetRatio(context: Context, ratio: Float) {
            prefs(context).edit().putFloat(PREF_RESET_RATIO, ratio).apply()
        }

        fun getSwipeCooldownMs(context: Context): Long {
            return prefs(context).getLong(PREF_SWIPE_COOLDOWN_MS, DEFAULT_SWIPE_COOLDOWN_MS)
        }

        fun setSwipeCooldownMs(context: Context, cooldownMs: Long) {
            prefs(context).edit().putLong(PREF_SWIPE_COOLDOWN_MS, cooldownMs).apply()
        }

        fun resetToDefaults(context: Context) {
            prefs(context).edit()
                .putString(PREF_TARGET_PACKAGE, DEFAULT_TARGET_PACKAGE)
                .putFloat(PREF_NEAR_END_RATIO, DEFAULT_NEAR_END_RATIO)
                .putFloat(PREF_RESET_RATIO, DEFAULT_RESET_RATIO)
                .putLong(PREF_SWIPE_COOLDOWN_MS, DEFAULT_SWIPE_COOLDOWN_MS)
                .apply()
        }

        private fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Last seen progress ratio (0.0 - 1.0). -1f = abhi tak kuch dekha nahi
    private var lastProgressRatio = -1f

    // True jab progress ek baar NEAR_END_RATIO tak pahunch chuka ho —
    // isse ye confirm hota hai ki reset "loop" hai, video switch ki wajah se
    // achanak progress 0 dikhna nahi
    private var wasNearEnd = false

    // Last time (elapsedRealtime) jab ratio near-end range mein dekha gaya tha.
    // Real loop mein end -> restart transition turant hota hai, isliye is
    // timestamp aur reset ke beech ka gap chota hona chahiye
    private var nearEndTimestamp = 0L

    private var lastSwipeTime = 0L

    // Is check cycle mein "Ad" label mila ya nahi (progress node na milne par
    // ad-detection ke liye use hota hai)
    private var foundAdLabelThisCheck = false

    // Jab se "Ad" (bina progress ke) pehli baar dikha — isse itna time guzarne
    // ke baad hi hum khud swipe karte hain (turant nahi, thoda dikhne do)
    private var adFirstSeenTime = 0L

    // True jab target app hi foreground mein ho. Isse poll loop sirf tabhi
    // chalta hai jab zaroorat ho — baaki apps use karte waqt battery bachti hai
    private var isTargetForeground = false

    private val pollHandler = Handler(Looper.getMainLooper())

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isTargetForeground) return

            checkForegroundProgress()

            // Near-end range mein tez poll karo (loop ka exact moment
            // catch karne ke liye), baaki time dheeme — battery bachao
            val nextDelay = if (wasNearEnd) FAST_POLL_INTERVAL_MS else SLOW_POLL_INTERVAL_MS
            pollHandler.postDelayed(this, nextDelay)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        instance = this

        // Agar service connect hote hi already target app foreground mein ho
        setTargetForeground(isCurrentWindowTargetApp())

        Log.d(TAG, "AutoSwipe Accessibility Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {

        if (event == null) return

        // Window state change se foreground app track karo — isi se poll
        // loop start/stop hota hai, taaki target app ke bahar poll na ho
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()
            setTargetForeground(isWatchedPackage(applicationContext, packageName))
        }

        // Events jab bhi aate hain turant check kar lo — baaki poll loop
        // hamesha chal hi raha hai (jab foreground ho) reliability ke liye
        if (
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            checkForegroundProgress()
        }
    }

    private fun isCurrentWindowTargetApp(): Boolean {
        val root = try {
            rootInActiveWindow
        } catch (e: Exception) {
            null
        } ?: return false

        val packageName = root.packageName?.toString()

        @Suppress("DEPRECATION")
        root.recycle()

        return isWatchedPackage(applicationContext, packageName)
    }

    private fun setTargetForeground(isForeground: Boolean) {

        if (isForeground == isTargetForeground) return

        isTargetForeground = isForeground
        pollHandler.removeCallbacks(pollRunnable)

        if (isForeground) {
            pollHandler.post(pollRunnable)
        } else {
            // Target app se bahar nikalte hi state reset karo — warna wapas
            // aane par purani wasNearEnd/lastProgressRatio ek naye/alag video
            // ko galti se "loop" samajh sakti hai
            wasNearEnd = false
            lastProgressRatio = -1f
            adFirstSeenTime = 0L
        }
    }

    private fun checkForegroundProgress() {

        val root = try {
            rootInActiveWindow
        } catch (e: Exception) {
            Log.w(TAG, "rootInActiveWindow failed", e)
            null
        } ?: return

        try {
            val packageName = root.packageName?.toString() ?: return

            // Sirf configured target app(s) ko inspect karenge
            if (!isWatchedPackage(applicationContext, packageName)) {
                return
            }

            foundAdLabelThisCheck = false
            val foundProgress = inspectNode(root)

            if (!foundProgress && foundAdLabelThisCheck) {
                handleAdDetected()
            } else {
                // Ab progress mil gaya (ya "Ad" label bhi nahi mila) —
                // purana ad-timer clear karo taaki stale na rahe
                adFirstSeenTime = 0L
            }
        } catch (e: Exception) {
            Log.w(TAG, "inspectNode failed", e)
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }

    /**
     * Tree ko depth-first traverse karta hai. Pehla matching progress node milte hi
     * rukk jaata hai — isse ek hi event mein multiple progress-jaise nodes (jaise
     * buffered vs playback range) ek doosre ki state ko override nahi karte.
     * Returns true jab koi matching node mil jaaye, taaki upar tak search band ho.
     */
    private fun inspectNode(node: AccessibilityNodeInfo?): Boolean {

        if (node == null) return false

        val className = node.className?.toString() ?: ""

        // YouTube ka video progress node "android.widget.SeekBar" hai.
        // Tree mein ek aur indeterminate "android.widget.ProgressBar" (buffering
        // spinner) bhi hota hai jiska rangeInfo hamesha min=max=0 hota hai —
        // usse reject karne ke liye max > min zaroori hai.
        if (className.contains("ProgressBar", ignoreCase = true) ||
            className.contains("SeekBar", ignoreCase = true)
        ) {
            // rootInActiveWindow se mila node stale ho sakta hai (purani
            // contentDescription/rangeInfo) — refresh() se view ka latest
            // state force karo
            node.refresh()

            val rangeInfo = node.rangeInfo

            if (rangeInfo != null && rangeInfo.max > rangeInfo.min) {
                handleProgress(rangeInfo.min, rangeInfo.max, rangeInfo.current)
                return true
            }

            // YouTube ka SeekBar rangeInfo set hi nahi karta — sirf
            // contentDescription mein "X minutes Y seconds of A minutes B
            // seconds" jaisa human-readable text deta hai. Usse parse karo.
            val parsed = parseElapsedAndTotalSeconds(node.contentDescription)
            if (parsed != null) {
                val (elapsedSeconds, totalSeconds) = parsed
                handleProgress(0f, totalSeconds, elapsedSeconds)
                return true
            }
        }

        // Instagram jaisi sponsored posts (product carousel wagera) ka koi
        // progress signal nahi hota — inhe explicitly "Ad" text/description
        // se label kiya jaata hai. Isse note kar lo taaki agar progress kahin
        // na mile to bhi hum jaan sakein ki ye ek "khatam na hone waali" ad hai
        if (node.text?.toString() == "Ad" || node.contentDescription?.toString() == "Ad") {
            foundAdLabelThisCheck = true
        }

        // Children ko recursively inspect karo, pehla match milte hi ruk jao
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = try {
                inspectNode(child)
            } finally {
                @Suppress("DEPRECATION")
                child.recycle()
            }
            if (found) return true
        }

        return false
    }

    /**
     * Progress ke bina "Ad" label mila — matlab ye ek aisi sponsored post hai
     * jo khud kabhi "khatam" nahi hogi (product carousel wagera). Thodi der
     * dikhne dene ke baad khud swipe kar do.
     */
    private fun handleAdDetected() {

        if (!isAutoSwipeEnabled(applicationContext)) return

        val now = SystemClock.elapsedRealtime()

        if (adFirstSeenTime == 0L) {
            adFirstSeenTime = now
            return
        }

        val swipeCooldownMs = getSwipeCooldownMs(applicationContext)

        if (now - adFirstSeenTime >= AD_SKIP_DELAY_MS && now - lastSwipeTime > swipeCooldownMs) {
            Log.d(TAG, "🚫 AD detected (no progress) -> auto swipe")

            lastSwipeTime = now
            adFirstSeenTime = 0L
            performSwipeUp()
        }
    }

    /**
     * Progress node se mila hua min/max/current process karta hai aur
     * Short khatam hone par (loop detect hone par) auto-swipe trigger karta hai.
     */
    private fun handleProgress(min: Float, max: Float, current: Float) {

        // Max 0 ya invalid ho to divide-by-zero se bacho
        if (max <= min) return

        // Auto-swipe toggle off ho to kuch mat karo
        if (!isAutoSwipeEnabled(applicationContext)) return

        val ratio = ((current - min) / (max - min)).coerceIn(0f, 1f)

        val nearEndRatio = getNearEndRatio(applicationContext)
        val resetRatio = getResetRatio(applicationContext)
        val swipeCooldownMs = getSwipeCooldownMs(applicationContext)

        Log.d(
            TAG,
            "🎯 PROGRESS | current=$current | max=$max | ratio=$ratio | wasNearEnd=$wasNearEnd"
        )

        // Step 1: near-end mark karo (baar-baar update karo jab tak yahin ho,
        // taaki paused/slow playback ke case mein bhi timestamp fresh rahe)
        if (ratio >= nearEndRatio) {
            wasNearEnd = true
            nearEndTimestamp = SystemClock.elapsedRealtime()
        }

        // Step 2: agar hum pehle near-end the aur ab achanak ratio bahut kam ho gaya
        // -> ye loop ho sakta hai, matlab Short khatam hoke dobara shuru hua
        if (wasNearEnd && ratio <= resetRatio && lastProgressRatio > ratio) {

            val now = SystemClock.elapsedRealtime()

            val drop = lastProgressRatio - ratio
            val minLoopDrop = (nearEndRatio - resetRatio) * MIN_LOOP_DROP_FRACTION
            val elapsedSinceNearEnd = now - nearEndTimestamp

            // Real loop mein drop bada aur turant hota hai. Manual scrub mein
            // ya to drop chota-chota (multiple events) hota hai ya zyada time
            // leta hai — dono cases mein ye false hoga aur swipe skip hogi
            val looksLikeNaturalLoop =
                drop >= minLoopDrop && elapsedSinceNearEnd <= MAX_LOOP_TRANSITION_MS

            if (!looksLikeNaturalLoop) {
                Log.d(
                    TAG,
                    "⏭️ Ignored (manual scrub jaisa lag raha hai) | drop=$drop | " +
                            "elapsed=$elapsedSinceNearEnd"
                )
            } else if (now - lastSwipeTime > swipeCooldownMs) {
                Log.d(TAG, "✅ SHORT ENDED (loop detected) -> auto swipe")

                lastSwipeTime = now
                performSwipeUp()

                // State sirf tabhi reset karo jab swipe actually hua ho —
                // warna cooldown ki wajah se blocked loop ka swipe hamesha ke
                // liye miss ho jaata (agla event isi loop ko dobara detect
                // nahi karega kyunki wasNearEnd already false ho chuka hota)
                wasNearEnd = false
            }
        }

        lastProgressRatio = ratio
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service interrupted")
    }

    override fun onDestroy() {

        pollHandler.removeCallbacks(pollRunnable)

        instance = null

        Log.d(TAG, "AutoSwipe Accessibility Service destroyed")

        super.onDestroy()
    }

    // -----------------------------------------
    // SWIPE
    // -----------------------------------------

    fun performSwipeUp() {

        val displayMetrics = resources.displayMetrics

        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val centerX = screenWidth / 2f

        val startY = screenHeight * 0.75f
        val endY = screenHeight * 0.25f

        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0,
                    500
                )
            )
            .build()

        dispatchGesture(gesture, null, null)

        Log.d(TAG, "SWIPE UP dispatched")
    }
}