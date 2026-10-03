package com.codsuporte

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Menu flutuante: uma bolha arrastável que mostra o ping ao vivo
 * e abre um painel com ferramentas de sistema.
 */
class OverlayService : Service() {

    companion object {
        const val CHANNEL = "cod_suporte"
        const val ACTION_STOP = "com.codsuporte.STOP"

        @Volatile
        var isRunning = false

        val BLACK = 0xFF0B0B10.toInt()
        val PANEL = 0xF2121219.toInt()
        val CARD = 0xFF1B1B27.toInt()
        val RED = 0xFFE5173F.toInt()
        val PURPLE = 0xFF8A2BE2.toInt()
        val PURPLE_L = 0xFFB47CFF.toInt()
        val PINK = 0xFFFF4D8D.toInt()
        val TEXT = 0xFFEDEDF5.toInt()
        val MUTED = 0xFF9A9AB0.toInt()
    }

    private lateinit var wm: WindowManager
    private lateinit var ctx: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var container: LinearLayout
    private lateinit var bubble: TextView
    private lateinit var scroll: ScrollView
    private lateinit var params: WindowManager.LayoutParams

    private val handler = Handler(Looper.getMainLooper())
    private var viewAdded = false
    private var expanded = false
    @Volatile private var alive = true
    private var silent = false

    private lateinit var tvPing: TextView
    private lateinit var tvNet: TextView
    private lateinit var tvTarget: TextView
    private lateinit var tvRam: TextView
    private lateinit var tvBat: TextView
    private lateinit var tvTimer: TextView
    private lateinit var btnBreak: TextView
    private lateinit var swDnd: Switch
    private lateinit var swAwake: Switch
    private lateinit var seekBright: SeekBar
    private lateinit var seekVol: SeekBar

    private val history = ArrayDeque<Int>()
    private var sessionStart = SystemClock.elapsedRealtime()
    private var breakMin = 45
    private var nextBreakAt = 0L

    // ------------------------------------------------------------ ciclo de vida

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("cod", MODE_PRIVATE)
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        isRunning = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        ctx = ContextThemeWrapper(this, android.R.style.Theme_Material)
        breakMin = prefs.getInt("break_min", 45)
        scheduleBreak()
        buildOverlay()
        startPing()
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        alive = false
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        if (viewAdded) {
            try {
                wm.removeView(container)
            } catch (_: Exception) {
            }
            viewAdded = false
        }
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Menu flutuante", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        @Suppress("DEPRECATION")
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("CoD Suporte ativo")
            .setContentText("Toque na bolha flutuante para abrir o menu")
            .setContentIntent(open)
            .addAction(0, "Parar", stop)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    // ------------------------------------------------------------ utilidades de UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun rounded(color: Int, radiusDp: Int, strokeDp: Int = 0, stroke: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
        }

    private fun bubbleBg(ring: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(0xEB0B0B10.toInt())
        setStroke(dp(3), ring)
    }

    private fun label(t: String, size: Float = 12f, color: Int = MUTED, bold: Boolean = false) =
        TextView(ctx).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun section(col: LinearLayout, title: String) {
        col.addView(label(title.uppercase(), 11f, PURPLE_L, true).apply {
            letterSpacing = 0.12f
            setPadding(0, dp(14), 0, dp(6))
        })
    }

    private fun button(t: String, filled: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        text = t
        gravity = Gravity.CENTER
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(TEXT)
        setPadding(dp(10), dp(11), dp(10), dp(11))
        background = if (filled) {
            GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(RED, PURPLE)
            ).apply { cornerRadius = dp(10).toFloat() }
        } else {
            rounded(CARD, 10, 1, PURPLE)
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
    }

    private fun switchRow(col: LinearLayout, title: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch {
        val s = Switch(ctx).apply {
            text = title
            textSize = 13f
            setTextColor(TEXT)
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(PURPLE_L, 0xFF6B6B7B.toInt()))
            trackTintList = ColorStateList(states, intArrayOf(0x808A2BE2.toInt(), 0x40FFFFFF))
            isChecked = checked
            setPadding(0, dp(4), 0, dp(4))
            setOnCheckedChangeListener { _, b -> if (!silent) onChange(b) }
        }
        col.addView(s)
        return s
    }

    private fun setSwitch(s: Switch, v: Boolean) {
        silent = true
        s.isChecked = v
        silent = false
    }

    private fun sliderRow(
        col: LinearLayout, title: String, max: Int, value: Int, onChange: (Int) -> Unit
    ): SeekBar {
        col.addView(label(title, 12f, TEXT).apply { setPadding(0, dp(6), 0, 0) })
        val sb = SeekBar(ctx).apply {
            this.max = max
            progress = value
            progressTintList = ColorStateList.valueOf(PURPLE)
            thumbTintList = ColorStateList.valueOf(RED)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) onChange(p)
                }

                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        col.addView(sb)
        return sb
    }

    // ------------------------------------------------------------ montagem do overlay

    private fun buildOverlay() {
        container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        bubble = TextView(ctx).apply {
            text = "--"
            gravity = Gravity.CENTER
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT)
            background = bubbleBg(PURPLE)
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
        }

        scroll = buildPanel()
        scroll.visibility = View.GONE

        container.addView(bubble)
        container.addView(scroll)

        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (prefs.getBoolean("awake", false)) {
            flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        }
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(80)
        }

        bubble.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f
            var moved = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x; startY = params.y
                        touchX = e.rawX; touchY = e.rawY
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - touchX).toInt()
                        val dy = (e.rawY - touchY).toInt()
                        if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                        if (moved) {
                            params.x = (startX + dx).coerceAtLeast(0)
                            params.y = (startY + dy).coerceAtLeast(0)
                            wm.updateViewLayout(container, params)
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) toggle()
                }
                return true
            }
        })

        wm.addView(container, params)
        viewAdded = true
    }

    private fun buildPanel(): ScrollView {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }

        col.addView(label("COD SUPORTE", 15f, RED, true).apply { letterSpacing = 0.12f })
        col.addView(label("Ferramentas de sistema. Não altera o jogo."))

        // --- monitor ---
        section(col, "Monitor")
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 10)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        tvPing = label("Ping  medindo…", 14f, PURPLE_L, true)
        tvNet = label("Jitter –  •  Perda –")
        tvTarget = label("Alvo: ${prefs.getString("ping_host", "1.1.1.1")}:${prefs.getInt("ping_port", 443)}", 11f)
        tvRam = label("RAM  –", 12f, TEXT)
        tvBat = label("Bateria  –", 12f, TEXT)
        tvTimer = label("Sessão  00:00:00", 12f, TEXT)
        for (v in listOf(tvPing, tvNet, tvTarget, tvRam, tvBat, tvTimer)) card.addView(v)
        col.addView(card)

        // --- modo jogo ---
        section(col, "Modo jogo")
        col.addView(button("⚡ ATIVAR MODO JOGO", true) { gameMode() })
        col.addView(label("Liga Não Perturbe e tela ligada, limpa a RAM e abre o CoD Mobile.", 10f).apply {
            setPadding(0, dp(4), 0, 0)
        })

        // --- ajustes ---
        section(col, "Ajustes")
        swDnd = switchRow(col, "Não perturbar (mantém som do jogo)", Tools.isDndOn(this)) { on ->
            if (!Tools.setDnd(this, on)) {
                toast("Conceda acesso a Não Perturbe no app principal")
                setSwitch(swDnd, false)
            }
        }
        swAwake = switchRow(col, "Manter tela ligada", prefs.getBoolean("awake", false)) { on ->
            prefs.edit().putBoolean("awake", on).apply()
            applyAwake(on)
        }
        seekBright = sliderRow(col, "Brilho da tela", 255, Tools.getBrightness(this)) { p ->
            if (!Tools.setBrightness(this, p)) {
                toast("Conceda 'Modificar configurações do sistema' no app principal")
                seekBright.progress = Tools.getBrightness(this)
            }
        }
        seekVol = sliderRow(col, "Volume de mídia", Tools.volumeMax(this), Tools.volume(this)) { p ->
            Tools.setVolume(this, p)
        }

        // --- utilidades ---
        section(col, "Utilidades")
        col.addView(button("🧹 Limpar RAM", false) { cleanRam() })
        btnBreak = button(breakText(), false) { cycleBreak() }
        col.addView(btnBreak)
        col.addView(button("🎮 Abrir CoD Mobile", false) { openGame() })
        col.addView(button("📶 Painel de internet", false) {
            startActivity(
                Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        })
        col.addView(button("↺ Zerar cronômetro da sessão", false) {
            sessionStart = SystemClock.elapsedRealtime()
            scheduleBreak()
            toast("Cronômetro zerado")
        })
        col.addView(button("✕ Fechar menu flutuante", false) { stopSelf() })

        return ScrollView(ctx).apply {
            background = rounded(PANEL, 14, 1, PURPLE)
            isVerticalScrollBarEnabled = false
            addView(col)
        }
    }

    private fun toggle() {
        expanded = !expanded
        if (expanded) {
            refreshControls()
            val screenH = resources.displayMetrics.heightPixels
            val child = scroll.getChildAt(0)
            child.measure(
                View.MeasureSpec.makeMeasureSpec(dp(290), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val panelH = minOf(child.measuredHeight, (screenH * 0.68f).toInt())
            scroll.layoutParams = LinearLayout.LayoutParams(dp(290), panelH).apply { topMargin = dp(8) }
            params.y = params.y.coerceAtMost(screenH - dp(56) - panelH - dp(24)).coerceAtLeast(0)
            scroll.visibility = View.VISIBLE
        } else {
            scroll.visibility = View.GONE
        }
        wm.updateViewLayout(container, params)
    }

    /** Sincroniza switches e sliders com o estado real do sistema. */
    private fun refreshControls() {
        setSwitch(swDnd, Tools.isDndOn(this))
        seekBright.progress = Tools.getBrightness(this)
        seekVol.progress = Tools.volume(this)
    }

    // ------------------------------------------------------------ funções

    private fun applyAwake(on: Boolean) {
        params.flags = if (on) {
            params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        }
        wm.updateViewLayout(container, params)
    }

    private fun cleanRam() {
        toast("Limpando…")
        thread(isDaemon = true) {
            val (apps, freed) = Tools.cleanRam(this)
            handler.post { toast("$apps apps encerrados • ~$freed MB liberados") }
        }
    }

    private fun gameMode() {
        if (Tools.setDnd(this, true)) {
            setSwitch(swDnd, true)
        } else {
            toast("Dica: conceda acesso a Não Perturbe no app principal")
        }
        setSwitch(swAwake, true)
        prefs.edit().putBoolean("awake", true).apply()
        applyAwake(true)

        sessionStart = SystemClock.elapsedRealtime()
        scheduleBreak()

        thread(isDaemon = true) {
            val (apps, freed) = Tools.cleanRam(this)
            handler.post {
                toast("Modo jogo ativo • $apps apps encerrados • ~$freed MB liberados")
                if (expanded) toggle()
                openGame()
            }
        }
    }

    private fun openGame() {
        for (p in Tools.GAME_PACKAGES) {
            val i = packageManager.getLaunchIntentForPackage(p)
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                return
            }
        }
        toast("CoD Mobile não encontrado neste aparelho")
    }

    // --- lembrete de pausa ---

    private fun breakText() = if (breakMin == 0) "⏰ Lembrete de pausa: desligado" else "⏰ Lembrete de pausa: $breakMin min"

    private fun cycleBreak() {
        breakMin = when (breakMin) {
            0 -> 30
            30 -> 45
            45 -> 60
            else -> 0
        }
        prefs.edit().putInt("break_min", breakMin).apply()
        btnBreak.text = breakText()
        scheduleBreak()
    }

    private fun scheduleBreak() {
        nextBreakAt = if (breakMin > 0) SystemClock.elapsedRealtime() + breakMin * 60_000L else 0L
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        v.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // --- ping ---

    private fun startPing() {
        thread(isDaemon = true) {
            while (alive) {
                val host = prefs.getString("ping_host", "1.1.1.1") ?: "1.1.1.1"
                val port = prefs.getInt("ping_port", 443)
                val ms = Tools.tcpPing(host, port)
                handler.post { onPing(ms, host, port) }
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun onPing(ms: Int, host: String, port: Int) {
        if (!alive) return
        history.addLast(ms)
        if (history.size > 15) history.removeFirst()

        val ok = history.filter { it >= 0 }
        val avg = if (ok.isEmpty()) -1 else ok.average().toInt()
        val jitter = if (ok.size > 1) ok.zipWithNext { a, b -> abs(a - b) }.average().toInt() else 0
        val loss = (history.size - ok.size) * 100 / history.size

        val color = when {
            ms < 0 -> RED
            ms < 60 -> PURPLE_L
            ms < 120 -> PINK
            else -> RED
        }
        bubble.text = if (ms < 0) "×" else "$ms\nms"
        bubble.background = bubbleBg(color)
        tvPing.text = if (ms < 0) "Ping  sem resposta" else "Ping  $ms ms  (média $avg)"
        tvPing.setTextColor(color)
        tvNet.text = "Jitter $jitter ms  •  Perda $loss%"
        tvTarget.text = "Alvo: $host:$port"
    }

    // --- atualização por segundo ---

    private val tick = object : Runnable {
        override fun run() {
            if (!alive) return
            val now = SystemClock.elapsedRealtime()

            val el = (now - sessionStart) / 1000
            tvTimer.text = "Sessão  %02d:%02d:%02d".format(el / 3600, (el % 3600) / 60, el % 60)

            if (nextBreakAt > 0 && now >= nextBreakAt) {
                vibrate()
                toast("Hora de uma pausa! Você joga há ${el / 60} min.")
                nextBreakAt = now + breakMin * 60_000L
            }

            val r = Tools.ram(this@OverlayService)
            tvRam.text = "RAM livre  %.1f GB / %.1f GB".format(r.freeMb / 1024f, r.totalMb / 1024f)

            val b = Tools.battery(this@OverlayService)
            val hot = b.tempC >= 42f
            tvBat.text = "Bateria  ${b.pct}%${if (b.charging) " ⚡" else ""}  •  ${"%.1f".format(b.tempC)} °C${if (hot) "  ⚠ quente" else ""}"
            tvBat.setTextColor(if (hot) RED else TEXT)

            handler.postDelayed(this, 1000)
        }
    }
}
