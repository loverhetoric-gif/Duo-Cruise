package com.yuki.duocruise.main;

import android.content.Context;

/**
 * 画面の文言(多言語)。文言の原本は apps/duo/i18n/strings.py、書き出し先は res/values-*\/strings.xml。
 * Context を持たない所(KeyMap など)からも引けるように、アプリの Context を覚えておく。
 * 動作記録(DuoLog)は開発用なので訳さない。
 */
final class Tx {
    private static Context app;

    private Tx() {}

    static void init(Context c) { app = c.getApplicationContext(); }

    static String s(int id, Object... args) {
        Context c = app;
        if (c == null) return "";
        return args.length == 0 ? c.getString(id) : c.getString(id, args);
    }
}
