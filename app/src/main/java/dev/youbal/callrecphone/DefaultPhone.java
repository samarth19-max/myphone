package dev.youbal.callrecphone;

import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.telecom.TelecomManager;
import androidx.annotation.RequiresApi;

/** Whether this app is the default phone app, and how to ask for it. Android 10+ uses roles; Android 8 and 9 use the older setting. */
final class DefaultPhone {
    private DefaultPhone() {}

    static boolean held(Context context) {
        if (Build.VERSION.SDK_INT >= 29) return Roles.held(context);
        TelecomManager telecom = context.getSystemService(TelecomManager.class);
        return telecom != null && context.getPackageName().equals(telecom.getDefaultDialerPackage());
    }

    /** The system screen that asks the user to make this app the default, or null if the phone does not offer it. */
    static Intent request(Context context) {
        if (Build.VERSION.SDK_INT >= 29) return Roles.request(context);
        return new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, context.getPackageName());
    }

    @RequiresApi(29)
    private static final class Roles {
        static boolean held(Context context) {
            RoleManager role = context.getSystemService(RoleManager.class);
            return role != null && role.isRoleHeld(RoleManager.ROLE_DIALER);
        }
        static Intent request(Context context) {
            RoleManager role = context.getSystemService(RoleManager.class);
            return role != null && role.isRoleAvailable(RoleManager.ROLE_DIALER) ? role.createRequestRoleIntent(RoleManager.ROLE_DIALER) : null;
        }
    }
}
