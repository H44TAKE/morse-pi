package br.pi.morse

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Atualização pelo GitHub: ao abrir o app, olha a última "release" do repositório; se for mais nova
 * que esta versão, mostra o botão "Atualizar", baixa o APK e abre o instalador do Android.
 */
object Updater {
    private val ui = Handler(Looper.getMainLooper())

    private fun parts(v: String) = v.trim().trimStart('v', 'V').split('.').map { it.toIntOrNull() ?: 0 }

    private fun isNewer(remote: String, local: String): Boolean {
        val a = parts(remote); val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Chame ao abrir o app. Se [manual], também avisa quando já está atualizado ou deu erro. */
    fun check(act: Activity, manual: Boolean = false) {
        thread {
            try {
                val c = URL("https://api.github.com/repos/${BuildConfig.REPO}/releases/latest")
                    .openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                val tag = j.getString("tag_name")
                val notes = j.optString("body", "").trim()
                if (isNewer(tag, BuildConfig.VERSION_NAME)) {
                    ui.post { if (!act.isFinishing) prompt(act, tag.trimStart('v', 'V'), notes) }
                } else if (manual) toast(act, "Já está na versão mais nova (${BuildConfig.VERSION_NAME}).")
            } catch (e: Exception) {
                if (manual) toast(act, "Não consegui verificar atualização: ${e.message}")
            }
        }
    }

    private fun toast(act: Activity, s: String) = ui.post { Toast.makeText(act, s, Toast.LENGTH_LONG).show() }

    private fun prompt(act: Activity, version: String, notes: String) {
        AlertDialog.Builder(act)
            .setTitle("Atualização disponível")
            .setMessage("Versão $version (você está na ${BuildConfig.VERSION_NAME})." +
                if (notes.isNotEmpty()) "\n\n$notes" else "")
            .setPositiveButton("Atualizar") { _, _ -> download(act) }
            .setNegativeButton("Depois", null)
            .show()
    }

    private fun download(act: Activity) {
        // Android exige liberar "instalar apps desconhecidos" para este app (uma vez só).
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(act, "Permita instalar por este app e toque em Atualizar de novo.", Toast.LENGTH_LONG).show()
            act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}")))
            return
        }
        Toast.makeText(act, "Baixando atualização…", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val dir = File(act.cacheDir, "update").apply { mkdirs() }
                val f = File(dir, BuildConfig.APK_NAME)
                val url = "https://github.com/${BuildConfig.REPO}/releases/latest/download/${BuildConfig.APK_NAME}"
                val c = URL(url).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = true
                c.connectTimeout = 15000; c.readTimeout = 30000
                if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
                c.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                val uri = FileProvider.getUriForFile(act, "${act.packageName}.fileprovider", f)
                ui.post {
                    act.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            } catch (e: Exception) {
                toast(act, "Falha ao baixar: ${e.message}")
            }
        }
    }
}
