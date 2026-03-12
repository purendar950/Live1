package com.upquiz.live

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import io.ably.lib.realtime.AblyRealtime
import io.ably.lib.realtime.Channel
import io.ably.lib.types.ClientOptions
import io.ably.lib.types.Message
import org.json.JSONObject

class FloatingService : Service() {

    companion object {
        var isRunning = false
        const val ABLY_KEY  = "wBfoUA.YHn4eA:CcalxKVSdtFhXiGdj45eNfAiSVn8PDxSLLzW8djRsDY"
        const val CH_NAME   = "upquiz-live"
        const val VOTE_CH   = "upquiz-votes"
        const val NOTIF_ID  = 101
        const val NOTIF_CH  = "upquiz_ch"
    }

    private lateinit var wm: WindowManager
    private var floatView: View? = null
    private var bubbleView: View? = null

    private var ably: AblyRealtime? = null
    private var channel: Channel? = null
    private var voteChannel: Channel? = null

    private var playerName = "Student"
    private var currentQNum = 0
    private var hasVoted = false
    private var myVote = -1
    private var timeLeft = 15
    private val handler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null
    private var autoCloseRunnable: Runnable? = null

    data class Score(var correct: Int = 0, var wrong: Int = 0,
                     var skipped: Int = 0, var streak: Int = 0, var total: Int = 0)
    private val S = Score()

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotifChannel()
        startForeground(NOTIF_ID, buildNotif("Quiz का इंतज़ार है..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        playerName = intent?.getStringExtra("player_name") ?: "Student"
        showBubble()
        connectAbly()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        removeFloat(); removeBubble()
        try { ably?.close() } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?) = null

    // ── ABLY ──
    private fun connectAbly() {
        try {
            val opts = ClientOptions(ABLY_KEY)
            opts.clientId = playerName
            ably = AblyRealtime(opts)
            channel     = ably!!.channels.get(CH_NAME)
            voteChannel = ably!!.channels.get(VOTE_CH)

            ably!!.connection.on(io.ably.lib.realtime.ConnectionState.connected) {
                handler.post { updateNotif("✅ Connected — Question का इंतज़ार") }
                try { voteChannel!!.presence.enter(playerName, null) } catch (e: Exception) {}
            }

            channel!!.subscribe("question") { msg -> handler.post { onQuestion(msg) } }
            channel!!.subscribe("reveal")   { msg -> handler.post { onReveal(msg) }   }
            channel!!.subscribe("end")      { _   -> handler.post { onEnd() }          }

        } catch (e: Exception) {
            updateNotif("❌ Connection Error")
        }
    }

    // ── QUESTION → POPUP ──
    private fun onQuestion(msg: Message) {
        val d = try { JSONObject(msg.data.toString()) } catch (e: Exception) { return }
        currentQNum = d.optInt("num", 0)
        hasVoted = false; myVote = -1; timeLeft = 15

        val q      = d.optString("q", "")
        val hi     = d.optString("hi", "")
        val ch     = d.optInt("ch", 0)
        val chName = d.optString("chName", "Chapter $ch")
        val num    = d.optInt("num", 0)
        val total  = d.optInt("total", 0)
        val opts   = mutableListOf<String>()
        val arr    = d.optJSONArray("opts")
        if (arr != null) for (i in 0 until arr.length()) {
            val v = arr.optString(i, ""); if (v.isNotEmpty()) opts.add(v)
        }

        removeBubble()
        removeFloat()
        showPopup(q, hi, ch, chName, num, total, opts)
        vibrate()
        updateNotif("Q$num: ${q.take(50)}")
    }

    // ── SHOW FLOATING POPUP ──
    private fun showPopup(
        q: String, hi: String,
        ch: Int, chName: String,
        num: Int, total: Int,
        opts: List<String>
    ) {
        val inflater = LayoutInflater.from(this)
        val v = inflater.inflate(R.layout.floating_card, null)

        v.findViewById<TextView>(R.id.tvChapter).text = "📚 Ch.$ch — $chName"
        v.findViewById<TextView>(R.id.tvQNum).text    = "Q$num of $total  •  ${S.correct}✓ ${S.wrong}✗"
        v.findViewById<TextView>(R.id.tvQ).text       = q
        val tvHi = v.findViewById<TextView>(R.id.tvHi)
        if (hi.isNotEmpty()) { tvHi.text = hi; tvHi.visibility = View.VISIBLE }
        else tvHi.visibility = View.GONE

        val tvTimer = v.findViewById<TextView>(R.id.tvTimer)
        tvTimer.text = "⏱ 15"

        // Build A/B/C/D/E buttons
        val container = v.findViewById<LinearLayout>(R.id.optContainer)
        val labels = listOf("A","B","C","D","E")
        opts.forEachIndexed { i, opt ->
            val btn = Button(this)
            btn.text = "${labels[i]}.  $opt"
            btn.tag  = i
            btn.setBackgroundColor(Color.parseColor("#1A2235"))
            btn.setTextColor(Color.parseColor("#F0F4FF"))
            btn.textSize = 13f
            btn.setPadding(24, 18, 24, 18)
            btn.setAllCaps(false)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, 8)
            btn.layoutParams = lp
            btn.setOnClickListener { castVote(i, container, labels, tvTimer) }
            container.addView(btn)
        }

        v.findViewById<ImageButton>(R.id.btnClose).setOnClickListener {
            cancelTimers(); removeFloat(); showBubble()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        params.y = 48

        floatView = v
        wm.addView(v, params)
        startCountdown(tvTimer, container)
    }

    private fun startCountdown(tvTimer: TextView, container: LinearLayout) {
        cancelTimers()
        timeLeft = 15
        timerRunnable = object : Runnable {
            override fun run() {
                tvTimer.text = "⏱ $timeLeft"
                tvTimer.setTextColor(when {
                    timeLeft <= 5  -> Color.parseColor("#FF4F6B")
                    timeLeft <= 10 -> Color.parseColor("#FF8C42")
                    else           -> Color.parseColor("#A78BFA")
                })
                if (timeLeft <= 0) {
                    if (!hasVoted) { S.skipped++; S.streak = 0; S.total++; tvTimer.text = "⏰ समय खत्म!" }
                    return
                }
                timeLeft--
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(timerRunnable!!)
    }

    private fun castVote(idx: Int, container: LinearLayout, labels: List<String>, tvTimer: TextView) {
        if (hasVoted) return
        hasVoted = true; myVote = idx
        cancelTimers()
        try {
            voteChannel?.publish("vote", JSONObject().apply {
                put("name", playerName); put("ans", idx); put("qNum", currentQNum)
            }.toString(), null)
        } catch (e: Exception) {}
        for (i in 0 until container.childCount) {
            val b = container.getChildAt(i) as? Button ?: continue
            if (b.tag as? Int == idx) b.setBackgroundColor(Color.parseColor("#5B4DE0"))
            else b.alpha = 0.4f
        }
        tvTimer.text = "📨 Vote भेजा!"
        tvTimer.setTextColor(Color.parseColor("#A78BFA"))
    }

    // ── REVEAL ──
    private fun onReveal(msg: Message) {
        val d = try { JSONObject(msg.data.toString()) } catch (e: Exception) { return }
        val correct = d.optInt("ans", -1)
        val exp     = d.optString("exp", "")
        cancelTimers()

        if (!hasVoted) { S.skipped++; S.streak = 0; S.total++ }
        else if (myVote == correct) { S.correct++; S.streak++ }
        else { S.wrong++; S.streak = 0 }
        if (hasVoted) S.total++

        val container = floatView?.findViewById<LinearLayout>(R.id.optContainer)
        val tvTimer   = floatView?.findViewById<TextView>(R.id.tvTimer)
        val tvExp     = floatView?.findViewById<TextView>(R.id.tvExp)

        container?.let {
            for (i in 0 until it.childCount) {
                val b = it.getChildAt(i) as? Button ?: continue
                b.alpha = 1f; b.isEnabled = false
                when {
                    i == correct -> b.setBackgroundColor(Color.parseColor("#059669"))
                    i == myVote && myVote != correct -> b.setBackgroundColor(Color.parseColor("#DC2626"))
                    else -> b.alpha = 0.3f
                }
            }
        }

        tvTimer?.text = when {
            !hasVoted -> "⏰ Skipped"
            myVote == correct -> "✅ Correct! (${S.correct}✓)"
            else -> "❌ Wrong! (${S.correct}✓)"
        }

        tvExp?.let {
            if (exp.isNotEmpty()) { it.text = "💡 $exp"; it.visibility = View.VISIBLE }
        }

        updateNotif("Score: ${S.correct}✓ ${S.wrong}✗")
        autoCloseRunnable = Runnable { removeFloat(); showBubble() }
        handler.postDelayed(autoCloseRunnable!!, 5000)
    }

    private fun onEnd() {
        removeFloat(); showBubble()
        updateNotif("Quiz Complete! ${S.correct}✓ ${S.wrong}✗ ${S.total} Questions")
    }

    // ── BUBBLE ──
    private fun showBubble() {
        removeBubble()
        val v = LayoutInflater.from(this).inflate(R.layout.floating_bubble, null)
        v.findViewById<TextView>(R.id.tvScore).text = "${S.correct}✓"
        v.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java)
                .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.END
        p.x = 12; p.y = 220
        var ix = 0f; var iy = 0f
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { ix = e.rawX - p.x; iy = e.rawY - p.y; false }
                MotionEvent.ACTION_MOVE -> { p.x=(e.rawX-ix).toInt(); p.y=(e.rawY-iy).toInt(); wm.updateViewLayout(v,p); true }
                else -> false
            }
        }
        bubbleView = v
        wm.addView(v, p)
    }

    private fun removeFloat()  { floatView?.let  { try { wm.removeView(it) } catch (e:Exception){} }; floatView  = null }
    private fun removeBubble() { bubbleView?.let { try { wm.removeView(it) } catch (e:Exception){} }; bubbleView = null }

    private fun cancelTimers() {
        timerRunnable?.let  { handler.removeCallbacks(it) }
        autoCloseRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun vibrate() {
        val vib = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            vib.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") vib.vibrate(250)
    }

    private fun createNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(NOTIF_CH, "UP Quiz Live", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotif(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, NOTIF_CH)
            .setContentTitle("UP Quiz Live 🎯")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true).build()
    }

    private fun updateNotif(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotif(text))
    }
}
