package com.yuki.duocruise.updater;

import android.app.Application;

/** アプリの入口。記録の送信と指示の取得を始める(ユーザー補助・定期確認・起動時のどれで起きても動く)。 */
public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Remote.init(this);
        UpdLog.init(this);
    }
}
