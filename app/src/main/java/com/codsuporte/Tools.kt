package com.codsuporte

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.provider.Settings
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Funções de sistema usadas pelo menu flutuante.
 * Tudo aqui usa APIs públicas do Android. Nada lê, injeta ou altera o jogo.
 */
object Tools {

    /** Pacotes conhecidos do Call of Duty Mobile (global, Garena e Tencent). */
    val GAME_PACKAGES = listOf(
        "com.activision.callofduty.shooter",
        "com.garena.game.codm",
        "com.tencent.tmgp.cod"
    )

    // ---------- permissões ----------

    fun canDrawOverlay(c: Context) = Settings.canDrawOverlays(c)

    fun canWriteSettings(c: Context) = Settings.System.canWrite(c)

    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)

    fun hasDndAccess(c: Context) = nm(c).isNotificationPolicyAccessGranted

    // ---------- ping (tempo de conexão TCP = ida e volta até o host) ----------

    /** Retorna a latência em ms, ou -1 se não houve resposta. */
    fun tcpPing(host: String, port: Int, timeoutMs: Int = 1500): Int {
        val start = System.nanoTime()
        return try {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            ((System.nanoTime() - start) / 1_000_000L).toInt()
        } catch (e: Exception) {
            -1
        }
    }

    // ---------- RAM ----------

    data class Ram(val freeMb: Long, val totalMb: Long)

    fun ram(c: Context): Ram {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return Ram(mi.availMem / 1_048_576L, mi.totalMem / 1_048_576L)
    }

    /**
     * Encerra processos em segundo plano de apps instalados pelo usuário
     * (nunca o jogo, o launcher nem este app). Devolve (apps tocados, MB liberados).
     * O Android pode reiniciar alguns deles; o ganho real varia de aparelho para aparelho.
     */
    fun cleanRam(c: Context): Pair<Int, Long> {
        val pm = c.packageManager
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val before = ram(c).freeMb

        val home = pm.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        )?.activityInfo?.packageName
        val protectedPkgs = GAME_PACKAGES + c.packageName

        var count = 0
        for (app in pm.getInstalledApplications(0)) {
            val isSystem = (app.flags and
                (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
            if (isSystem || app.packageName in protectedPkgs || app.packageName == home) continue
            am.killBackgroundProcesses(app.packageName)
            count++
        }
        Thread.sleep(700)
        val after = ram(c).freeMb
        return Pair(count, (after - before).coerceAtLeast(0))
    }

    // ---------- bateria ----------

    data class Bat(val pct: Int, val tempC: Float, val charging: Boolean)

    fun battery(c: Context): Bat {
        val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING ||
            st == BatteryManager.BATTERY_STATUS_FULL
        return Bat(if (level >= 0 && scale > 0) level * 100 / scale else -1, temp, charging)
    }

    // ---------- brilho ----------

    fun getBrightness(c: Context): Int = try {
        Settings.System.getInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    } catch (e: Exception) {
        128
    }

    /** Precisa da permissão "Modificar configurações do sistema". */
    fun setBrightness(c: Context, value: Int): Boolean {
        if (!canWriteSettings(c)) return false
        Settings.System.putInt(
            c.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
        Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value.coerceIn(1, 255))
        return true
    }

    // ---------- volume de mídia ----------

    private fun audio(c: Context) = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun volumeMax(c: Context) = audio(c).getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    fun volume(c: Context) = audio(c).getStreamVolume(AudioManager.STREAM_MUSIC)

    fun setVolume(c: Context, v: Int) {
        audio(c).setStreamVolume(AudioManager.STREAM_MUSIC, v.coerceIn(0, volumeMax(c)), 0)
    }

    // ---------- Não Perturbe ----------

    /**
     * Liga o modo "somente prioridade": bloqueia notificações e chamadas comuns,
     * mas mantém o som de mídia/jogo. Guarda o estado anterior para restaurar.
     */
    fun setDnd(c: Context, on: Boolean): Boolean {
        val n = nm(c)
        if (!n.isNotificationPolicyAccessGranted) return false
        val prefs = c.getSharedPreferences("cod", Context.MODE_PRIVATE)
        if (on) {
            val current = n.currentInterruptionFilter
            if (current != NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
                prefs.edit().putInt("prev_dnd", current).apply()
            }
            n.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        } else {
            n.setInterruptionFilter(
                prefs.getInt("prev_dnd", NotificationManager.INTERRUPTION_FILTER_ALL)
            )
        }
        return true
    }

    fun isDndOn(c: Context): Boolean {
        val n = nm(c)
        if (!n.isNotificationPolicyAccessGranted) return false
        val f = n.currentInterruptionFilter
        return f != NotificationManager.INTERRUPTION_FILTER_ALL &&
            f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }
}
