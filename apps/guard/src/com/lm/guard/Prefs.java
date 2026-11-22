package com.lm.guard;

import android.content.Context;
import android.content.SharedPreferences;

/* loaded from: classes.dex */
public class Prefs {
    static final String SP = "guard";
    public String host = "smtp.qq.com";
    public int port = 465;
    public boolean ssl = true;
    public String user = "";
    public String pass = "";
    public String to = "";
    public int cooldownSec = 60;
    public float minConfidence = 0.35f;
    public int minEyeDist = 16;
    public int keepPhotos = 200;
    public boolean autoStartOnBoot = false;
    public boolean autoShowOnWake = true;

    public static Prefs load(Context context) {
        SharedPreferences sharedPreferences = context.getSharedPreferences(SP, 0);
        Prefs prefs = new Prefs();
        prefs.host = sharedPreferences.getString("host", prefs.host);
        prefs.port = sharedPreferences.getInt("port", prefs.port);
        prefs.ssl = sharedPreferences.getBoolean("ssl", prefs.ssl);
        prefs.user = sharedPreferences.getString("user", prefs.user);
        prefs.pass = sharedPreferences.getString("pass", prefs.pass);
        prefs.to = sharedPreferences.getString("to", prefs.to);
        prefs.cooldownSec = sharedPreferences.getInt("cooldown", prefs.cooldownSec);
        prefs.minConfidence = sharedPreferences.getFloat("conf", prefs.minConfidence);
        prefs.minEyeDist = sharedPreferences.getInt("eyedist", prefs.minEyeDist);
        prefs.keepPhotos = sharedPreferences.getInt("keep", prefs.keepPhotos);
        prefs.autoStartOnBoot = sharedPreferences.getBoolean("autoboot", prefs.autoStartOnBoot);
        prefs.autoShowOnWake = sharedPreferences.getBoolean("autowake", prefs.autoShowOnWake);
        return prefs;
    }

    public void save(Context context) {
        SharedPreferences.Editor edit = context.getSharedPreferences(SP, 0).edit();
        edit.putString("host", this.host);
        edit.putInt("port", this.port);
        edit.putBoolean("ssl", this.ssl);
        edit.putString("user", this.user);
        edit.putString("pass", this.pass);
        edit.putString("to", this.to);
        edit.putInt("cooldown", this.cooldownSec);
        edit.putFloat("conf", this.minConfidence);
        edit.putInt("eyedist", this.minEyeDist);
        edit.putInt("keep", this.keepPhotos);
        edit.putBoolean("autoboot", this.autoStartOnBoot);
        edit.putBoolean("autowake", this.autoShowOnWake);
        edit.commit();
    }

    public boolean mailReady() {
        return this.host != null && this.host.length() > 0 && this.user != null && this.user.length() > 0 && this.pass != null && this.pass.length() > 0 && this.to != null && this.to.length() > 0;
    }
}
