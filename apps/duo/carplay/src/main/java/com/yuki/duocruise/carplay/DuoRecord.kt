package com.yuki.duocruise.carplay

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 記録を更新アプリ(Duo Cruise Market)へ送る。更新アプリがまとめて記録シートへ送る。
 *
 * 約束(実装の分担 2026-10-27)：
 * ブロードキャスト `com.yuki.duocruise.updater.LOG`、宛先パッケージ `com.yuki.duocruise.updater`、
 * 中身は kind / text / app / version。送る側は `com.yuki.duocruise.permission.LOG` を持つ
 * (carplay モジュールの AndroidManifest で宣言済み)。
 */
object DuoRecord {
    const val ACTION_LOG = "com.yuki.duocruise.updater.LOG"
    const val UPDATER_PACKAGE = "com.yuki.duocruise.updater"

    const val KIND_LOG = "log"
    const val KIND_STATE = "state"
    const val KIND_ERROR = "error"
    const val KIND_RESULT = "result"

    private const val TAG = "DuoCarPlay"
    private const val MAX_TEXT = 8000

    @JvmStatic
    fun send(context: Context, kind: String, text: String) {
        Log.i(TAG, "[$kind] $text")
        try {
            val app = context.applicationContext
            val intent = Intent(ACTION_LOG)
                .setPackage(UPDATER_PACKAGE)
                .putExtra("kind", kind)
                .putExtra("text", if (text.length > MAX_TEXT) text.substring(0, MAX_TEXT) + "…(省略)" else text)
                .putExtra("app", app.packageName)
                .putExtra("version", versionOf(app))
            app.sendBroadcast(intent)
        } catch (error: Exception) {
            Log.w(TAG, "記録を送れませんでした", error)
        }
    }

    private fun versionOf(context: Context): String = try {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        info.versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }
}
