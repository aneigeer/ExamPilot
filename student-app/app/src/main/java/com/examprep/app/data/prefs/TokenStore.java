package com.examprep.app.data.prefs;

import android.content.Context;
import android.content.SharedPreferences;

import com.examprep.app.data.model.Models;

/** 登录态（token + 当前用户），全应用共享 */
public class TokenStore {

    private static final String PREF = "auth";
    private static TokenStore instance;
    private final SharedPreferences sp;

    private TokenStore(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static synchronized TokenStore get(Context context) {
        if (instance == null) {
            instance = new TokenStore(context);
        }
        return instance;
    }

    public void save(String token, Models.User user) {
        sp.edit().putString("token", token)
                .putString("uid", user == null ? null : String.valueOf(user.id))
                .putString("uno", user == null ? null : user.studentNo)
                .putString("uname", user == null ? null : user.nickname)
                .putString("urole", user == null ? null : user.role)
                .apply();
    }

    public String token() {
        return sp.getString("token", null);
    }

    public boolean hasToken() {
        return token() != null;
    }

    public String userName() {
        return sp.getString("uname", "未登录");
    }

    public String userNo() {
        return sp.getString("uno", "");
    }

    public void clear() {
        sp.edit().clear().apply();
    }
}
