package com.codsuporte

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private class Perm(val title: String, val why: String, val check: () -> Boolean, val intent: () -> Intent) {
        lateinit var status: TextView
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var perms: List<Perm>
    private lateinit var tvService: TextView

    private val BLACK = OverlayService.BLACK
    private val CARD = OverlayService.CARD
    private val RED = OverlayService.RED
    private val PURPLE = OverlayService.PURPLE
    private val PURPLE_L = OverlayService.PURPLE_L
    private val TEXT = OverlayService.TEXT
    private val MUTED = OverlayService.MUTED

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("cod", MODE_PRIVATE)

        perms = listOf(
            Perm(
                "Sobrepor a outros apps", "Necessária para a bolha flutuante aparecer sobre o jogo.",
                { Tools.canDrawOverlay(this) },
                { Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")) }
            ),
            Perm(
                "Modificar configurações do sistema", "Necessária para o controle de brilho.",
                { Tools.canWriteSettings(this) },
                { Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")) }
            ),
            Perm(
                "Acesso a Não Perturbe", "Necessária para silenciar notificações durante a partida.",
                { Tools.hasDndAccess(this) },
                { Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }
            ),
            Perm(
                "Notificações", "Mostra o aviso fixo do serviço (obrigatório no Android 13+).",
                {
                    Build.VERSION.SDK_INT < 33 ||
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                },
                {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
            )
        )

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(36), dp(20), dp(36))
        }

        col.addView(text("COD SUPORTE", 28f, RED, true).apply { letterSpacing = 0.1f })
        col.addView(text("Menu flutuante de utilidades para jogar melhor", 13f, MUTED))
        col.addView(text("Usa só recursos oficiais do Android. Não lê, não modifica e não dá vantagem no jogo.", 12f, PURPLE_L).apply {
            setPadding(0, dp(8), 0, 0)
        })

        // --- permissões ---
        col.addView(sectionTitle("1. Permissões"))
        for (p in perms) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(CARD, 12, 1, 0x338A2BE2)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }
            card.addView(text(p.title, 14f, TEXT, true))
            card.addView(text(p.why, 12f, MUTED))
            p.status = text("", 12f, MUTED, true).apply { setPadding(0, dp(6), 0, 0) }
            card.addView(p.status)
            card.addView(button("Conceder", false) {
                if (p.title == "Notificações" && Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
                } else {
                    startActivity(p.intent())
                }
            })
            col.addView(card)
        }

        // --- ping ---
        col.addView(sectionTitle("2. Alvo do ping"))
        col.addView(text("Por padrão mede o tempo até a Cloudflare (1.1.1.1:443), que reflete a qualidade da sua internet. Você pode trocar por outro host/porta.", 12f, MUTED))
        val etHost = input("Host (ex.: 1.1.1.1)", prefs.getString("ping_host", "1.1.1.1") ?: "1.1.1.1", InputType.TYPE_CLASS_TEXT)
        val etPort = input("Porta (ex.: 443)", prefs.getInt("ping_port", 443).toString(), InputType.TYPE_CLASS_NUMBER)
        col.addView(etHost)
        col.addView(etPort)
        col.addView(button("Salvar alvo", false) {
            val host = etHost.text.toString().trim()
            val port = etPort.text.toString().trim().toIntOrNull()
            if (host.isEmpty() || port == null || port !in 1..65535) {
                Toast.makeText(this, "Host ou porta inválidos", Toast.LENGTH_SHORT).show()
            } else {
                prefs.edit().putString("ping_host", host).putInt("ping_port", port).apply()
                Toast.makeText(this, "Alvo salvo (vale no próximo ciclo de ping)", Toast.LENGTH_SHORT).show()
            }
        })

        // --- iniciar ---
        col.addView(sectionTitle("3. Menu flutuante"))
        tvService = text("", 13f, TEXT, true)
        col.addView(tvService)
        col.addView(button("▶ INICIAR MENU FLUTUANTE", true) { startOverlay() })
        col.addView(button("■ Parar menu flutuante", false) {
            stopService(Intent(this, OverlayService::class.java))
            tvService.postDelayed({ refresh() }, 300)
        })

        col.addView(sectionTitle("O que o menu faz"))
        col.addView(text(
            "• Ping, jitter e perda de pacotes ao vivo na bolha\n" +
                "• RAM livre, bateria e temperatura (alerta acima de 42 °C)\n" +
                "• Modo jogo em um toque: Não Perturbe + tela ligada + limpar RAM + abrir o jogo\n" +
                "• Brilho e volume de mídia\n" +
                "• Limpeza de RAM de apps em segundo plano\n" +
                "• Cronômetro de sessão e lembrete de pausa com vibração\n" +
                "• Atalho para o painel de internet",
            12f, MUTED
        ))

        val scroll = ScrollView(this).apply {
            setBackgroundColor(BLACK)
            addView(col)
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun refresh() {
        for (p in perms) {
            val ok = p.check()
            p.status.text = if (ok) "✓ Concedida" else "✗ Pendente"
            p.status.setTextColor(if (ok) PURPLE_L else RED)
        }
        val on = OverlayService.isRunning
        tvService.text = if (on) "● Ativo" else "○ Parado"
        tvService.setTextColor(if (on) PURPLE_L else MUTED)
    }

    private fun startOverlay() {
        if (!Tools.canDrawOverlay(this)) {
            Toast.makeText(this, "Conceda 'Sobrepor a outros apps' primeiro", Toast.LENGTH_LONG).show()
            startActivity(perms[0].intent())
            return
        }
        startForegroundService(Intent(this, OverlayService::class.java))
        Toast.makeText(this, "Menu ativo. Abra o jogo e toque na bolha.", Toast.LENGTH_LONG).show()
        tvService.postDelayed({ refresh() }, 500)
    }

    // ------------------------------------------------------------ helpers de UI

    private fun rounded(color: Int, radiusDp: Int, strokeDp: Int = 0, stroke: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
        }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun sectionTitle(t: String) = text(t.uppercase(), 12f, PURPLE_L, true).apply {
        letterSpacing = 0.12f
        setPadding(0, dp(26), 0, dp(4))
    }

    private fun input(hint: String, value: String, type: Int) = EditText(this).apply {
        setHint(hint)
        setText(value)
        inputType = type
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        textSize = 14f
        background = rounded(CARD, 10, 1, PURPLE)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
    }

    private fun button(t: String, filled: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = t
        gravity = Gravity.CENTER
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(TEXT)
        setPadding(dp(12), dp(13), dp(12), dp(13))
        background = if (filled) {
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(RED, PURPLE))
                .apply { cornerRadius = dp(10).toFloat() }
        } else {
            rounded(CARD, 10, 1, PURPLE)
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
    }
}
