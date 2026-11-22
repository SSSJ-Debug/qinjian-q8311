package com.lm.guard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/* loaded from: classes.dex */
public class BootReceiver extends BroadcastReceiver {
    @Override // android.content.BroadcastReceiver
    public void onReceive(Context context, Intent intent) {
        try {
            if (Prefs.load(context).autoStartOnBoot) {
                context.startService(new Intent(context, (Class<?>) GuardService.class));
            }
        } catch (Throwable th) {
        }
    }
}
