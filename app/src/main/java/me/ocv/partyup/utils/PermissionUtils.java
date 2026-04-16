package me.ocv.partyup.utils;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class PermissionUtils {

    public static final int READ_EXTERNAL_STORAGE_REQUEST_CODE = 573;
    public static final int ALL_PERMISSIONS_REQUEST_CODE       = 100;

    /**
     * Checks for storage permission and requests it if it's not granted.
     *
     * @param activity The activity to use for checking and requesting permissions.
     *
     * @return true if the permission is already granted, false otherwise.
     */
    public static boolean checkAndRequestStoragePermission(Activity activity) {
        if (hasStoragePermission(activity)) {
            return true;
        } else {
            requestStoragePermission(activity);
            return false;
        }
    }

    public static boolean hasStoragePermission(Activity activity) {
        return ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED;
    }

    public static void requestStoragePermission(Activity activity) {
        ActivityCompat.requestPermissions(
                activity,
                new String[] { Manifest.permission.READ_EXTERNAL_STORAGE },
                READ_EXTERNAL_STORAGE_REQUEST_CODE
        );
    }

    public static boolean hasAllPermissions(Activity activity) {
        String[] permissions = getManifestPermissions(activity);
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(activity, permission) !=
                PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private static String[] getManifestPermissions(
            @NonNull Activity activity
    ) {
        try {
            PackageInfo info = activity.getPackageManager()
                                       .getPackageInfo(
                                               activity.getPackageName(),
                                               PackageManager.GET_PERMISSIONS
                                       );
            if (info.requestedPermissions != null) {
                return info.requestedPermissions;
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e("PERMISSION", "Unable to find permissions");
        }
        return new String[0];
    }

    public static void requestAllPermissions(Activity activity) {
        String[] permissions = getManifestPermissions(activity);
        List<String> permissionsToRequest = new ArrayList<>();
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(activity, permission) !=
                PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission);
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(
                    activity,
                    permissionsToRequest.toArray(new String[0]),
                    ALL_PERMISSIONS_REQUEST_CODE
            );
        }
    }

}
