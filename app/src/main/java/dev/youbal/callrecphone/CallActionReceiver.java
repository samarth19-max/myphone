package dev.youbal.callrecphone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Answer, Decline and End buttons on the call notification. */
public final class CallActionReceiver extends BroadcastReceiver {
    static final String ANSWER = "dev.youbal.callrecphone.ANSWER", DECLINE = "dev.youbal.callrecphone.DECLINE", END = "dev.youbal.callrecphone.END";
    @Override public void onReceive(Context context, Intent intent) {
        CallService service = CallService.instance;
        if (service != null && intent.getAction() != null) service.act(intent.getAction());
    }
}
