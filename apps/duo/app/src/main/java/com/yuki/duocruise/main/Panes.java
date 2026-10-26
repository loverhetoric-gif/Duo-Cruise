package com.yuki.duocruise.main;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;

/**
 * 左右の部品を用意する。
 * ・B の com.yuki.duocruise.carplay.CarPlayPane、D の com.yuki.duocruise.main.tv.TvPane が
 *   入っていれば、それを使う(約束どおりのメソッド名で呼ぶ)
 * ・まだ入っていなければ、同じ約束の仮の部品を使う
 * 本物がそろったら、このファイルの中だけを直接呼び出しに書き換えればよい(他は変えなくてよい)。
 */
final class Panes {
    private Panes() {}

    static final String CAR_CLASS = "com.yuki.duocruise.carplay.CarPlayPane";
    static final String TV_CLASS = "com.yuki.duocruise.main.tv.TvPane";

    static Pane car(Activity a) {
        try {
            Class<?> cls = Class.forName(CAR_CLASS);
            for (Constructor<?> k : cls.getConstructors()) {
                Class<?>[] p = k.getParameterTypes();
                if (p.length == 2 && p[0].isAssignableFrom(a.getClass()) && p[1].isInterface()) {
                    Object listener = Proxy.newProxyInstance(cls.getClassLoader(), new Class<?>[]{p[1]}, carListener());
                    return new Reflect(a, k.newInstance(a, listener), "CarPlay");
                }
            }
            DuoLog.error("CarPlayPane はあるが、(Context, Listener) の作り方が見つかりません。仮の部品で動きます");
        } catch (ClassNotFoundException e) {
            DuoLog.log("CarPlay: 本物の部品が未着のため、仮の部品で動きます");
        } catch (Throwable t) {
            DuoLog.error("CarPlay の部品を作れません: " + DuoLog.why(t));
        }
        return new StubCarPane(a);
    }

    static Pane tv(Activity a) {
        try {
            Class<?> cls = Class.forName(TV_CLASS);
            Constructor<?> k = cls.getConstructor(Activity.class);
            return new Reflect(a, k.newInstance(a), "YouTube");
        } catch (ClassNotFoundException e) {
            DuoLog.log("YouTube: 本物の部品が未着のため、仮の部品で動きます");
        } catch (Throwable t) {
            DuoLog.error("YouTube の部品を作れません: " + DuoLog.why(t));
        }
        return new StubTvPane(a);
    }

    /**
     * CarPlayPane が求める実行時の権限(static requiredPermissions())。部品が無ければ空。
     */
    static String[] carPermissions() {
        try {
            Object r = Class.forName(CAR_CLASS).getMethod("requiredPermissions").invoke(null);
            return r instanceof String[] ? (String[]) r : new String[0];
        } catch (ClassNotFoundException e) {
            return new String[0];
        } catch (Throwable t) {
            DuoLog.error("CarPlay の権限一覧を読めません: " + DuoLog.why(t));
            return new String[0];
        }
    }

    /**
     * CarPlayPane からの知らせ(Listener.onStatus / onRecord)。
     * onRecord は画面表示用(更新アプリへは CarPlayPane が送り済み)なので、ここからは送らない。
     */
    private static InvocationHandler carListener() {
        return (proxy, m, args) -> {
            String name = m.getName();
            if (m.getDeclaringClass() == Object.class) {
                if (name.equals("hashCode")) return System.identityHashCode(proxy);
                if (name.equals("equals")) return proxy == (args == null ? null : args[0]);
                if (name.equals("toString")) return "CarPlayListener";
            }
            if (name.equals("onRecord") && args != null && args.length == 2) {
                DuoLog.local(String.valueOf(args[0]), "CarPlay: " + args[1]);
            } else if (name.equals("onStatus") && args != null && args.length == 1) {
                DuoLog.local("state", "CarPlay の状態: " + args[0]);
            } else {
                DuoLog.local("log", "CarPlay: " + name + (args == null ? "" : " " + Arrays.toString(args)));
            }
            DuoActivity.stateChanged();
            Class<?> r = m.getReturnType();
            if (r == boolean.class) return false;
            if (r == int.class || r == long.class || r == short.class || r == byte.class) return 0;
            if (r == float.class || r == double.class) return 0.0;
            if (r == char.class) return '\0';
            return null;
        };
    }

    /** 本物の部品を、約束のメソッド名で呼ぶ。無いメソッドは何もしない。 */
    private static final class Reflect implements Pane {
        private final Context ctx;
        private final Object o;
        private final String name;
        private View view;

        Reflect(Context ctx, Object o, String name) { this.ctx = ctx; this.o = o; this.name = name; }

        private Method find(String m, Class<?>... types) {
            try { return o.getClass().getMethod(m, types); } catch (NoSuchMethodException e) { return null; }
        }

        private Object call(String m, Class<?>[] types, Object... args) {
            Method x = find(m, types);
            if (x == null) return null;
            try { return x.invoke(o, args); }
            catch (Throwable t) { DuoLog.error(name + "." + m + " で失敗: " + DuoLog.why(t)); return null; }
        }

        private void call0(String m) { call(m, new Class<?>[0]); }

        @Override public View view() {
            if (view == null) {
                Object v = call("view", new Class<?>[0]);
                if (v instanceof View) view = (View) v;
                else {
                    DuoLog.error(name + ".view() が画面を返しません");
                    TextView t = new TextView(ctx);
                    t.setText(name + " の画面がありません");
                    t.setTextColor(0xFF8FA0BC);
                    t.setGravity(Gravity.CENTER);
                    view = t;
                }
            }
            return view;
        }

        @Override public void start() { call0("start"); }
        @Override public void resume() { call0("onResume"); }
        @Override public void pause() { call0("onPause"); }
        @Override public void destroy() { call0("stop"); call0("destroy"); }

        @Override public boolean onKey(KeyEvent e) {
            Object r = call("onKey", new Class<?>[]{KeyEvent.class}, e);
            return r instanceof Boolean && (Boolean) r;
        }

        @Override public boolean onGenericMotion(MotionEvent e) {
            Object r = call("onGenericMotion", new Class<?>[]{MotionEvent.class}, e);
            return r instanceof Boolean && (Boolean) r;
        }

        @Override public String status() {
            Object r = call("status", new Class<?>[0]);
            return r == null ? "-" : String.valueOf(r);
        }

        @Override public String command(String cmd) {
            Object r = call("command", new Class<?>[]{String.class}, cmd);
            return r == null ? null : String.valueOf(r);
        }

        @Override public String name() { return name; }
        @Override public boolean real() { return true; }
    }
}
