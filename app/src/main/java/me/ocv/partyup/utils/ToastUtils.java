package me.ocv.partyup.utils;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

public final class ToastUtils {
    private static final String TAG = "ToastUtils";

    private ToastUtils() {
    }

    public static synchronized void show(
            Context context,
            String msg
    ) {
        if (context instanceof Activity) {
            Activity activity = (Activity) context;
            if (activity.isFinishing() || activity.isDestroyed()) {
                Log.i(TAG, "Context is finishing or destroyed: " + context);
                return;
            }
        }

        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(
                                                                    context.getApplicationContext(),
                                                                    msg,
                                                                    Toast.LENGTH_SHORT
                                                            )
                                                            .show());
    }

}
