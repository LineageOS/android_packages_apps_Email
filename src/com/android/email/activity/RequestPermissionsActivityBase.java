/*
 * Copyright (c) 2016, The Linux Foundation. All rights reserved.
 * Not a Contribution.
 *
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.email.activity;


import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Trace;
import android.widget.Toast;

import com.android.email.R;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Activity that asks the user for all {@link #getDesiredPermissions} if any of
 * {@link #getRequiredPermissions} are missing.
 *
 * NOTE: As a result of b/22095159, this can behave oddly in the case where the final permission
 * you are requesting causes an application restart.
 */
public abstract class RequestPermissionsActivityBase extends Activity {
    public static final String PREVIOUS_ACTIVITY_INTENT = "previous_intent";
    private static final int PERMISSIONS_REQUEST_ALL_PERMISSIONS = 1;

    /**
     * @return list of permissions that are needed in order for {@link #PREVIOUS_ACTIVITY_INTENT} to
     * operate. You only need to return a single permission per permission group you care about.
     */
    protected abstract String[] getRequiredPermissions();

    /**
     * @return list of permissions that would be useful for {@link #PREVIOUS_ACTIVITY_INTENT} to
     * operate. You only need to return a single permission per permission group you care about.
     */
    protected abstract String[] getDesiredPermissions();

    private Intent mPreviousActivityIntent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPreviousActivityIntent = (Intent) getIntent().getExtras().get(PREVIOUS_ACTIVITY_INTENT);

        // Only start a requestPermissions() flow when first starting this activity the first time.
        // The process is likely to be restarted during the permission flow (necessary to enable
        // permissions) so this is important to track.
        if (savedInstanceState == null) {
            requestPermissions();
        }
    }

    /**
     * If any permissions the Contacts app needs are missing, open an Activity
     * to prompt the user for these permissions. Moreover, finish the current activity.
     *
     * This is designed to be called inside {@link android.app.Activity#onCreate}
     */
    protected static boolean startPermissionActivity(Activity activity,
            String[] requiredPermissions, Class<?> newActivityClass) {
        if (!RequestPermissionsActivity.hasPermissions(activity, requiredPermissions)) {
            final Intent intent = new Intent(activity,  newActivityClass);
            intent.putExtra(PREVIOUS_ACTIVITY_INTENT, activity.getIntent());
            activity.startActivity(intent);
            activity.finish();
            return true;
        }
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[],
            int[] grantResults) {
        if (permissions != null && permissions.length > 0
                && isAllGranted(permissions, grantResults)) {
            mPreviousActivityIntent.setFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(mPreviousActivityIntent);
            finish();
            overridePendingTransition(0, 0);
        } else {
            Toast.makeText(this, R.string.missing_required_permission, Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private boolean isAllGranted(String permissions[], int[] grantResult) {
        for (int i = 0; i < permissions.length; i++) {
            if (grantResult[i] != PackageManager.PERMISSION_GRANTED
                    && isPermissionRequired(permissions[i])) {
                return false;
            }
        }
        return true;
    }

    private boolean isPermissionRequired(String p) {
        return Arrays.asList(getRequiredPermissions()).contains(p);
    }

    private void requestPermissions() {
        Trace.beginSection("requestPermissions");
        try {
            // Construct a list of missing permissions
            final ArrayList<String> unsatisfiedPermissions = new ArrayList<>();
            for (String permission : getDesiredPermissions()) {
                if (checkSelfPermission(permission)
                        != PackageManager.PERMISSION_GRANTED) {
                    unsatisfiedPermissions.add(permission);
                }
            }
            if (unsatisfiedPermissions.size() == 0) {
                throw new RuntimeException("Request permission activity was called even"
                        + " though all permissions are satisfied.");
            }
            requestPermissions(
                    unsatisfiedPermissions.toArray(new String[unsatisfiedPermissions.size()]),
                    PERMISSIONS_REQUEST_ALL_PERMISSIONS);
        } finally {
            Trace.endSection();
        }
    }

    protected static boolean hasPermissions(Context context, String[] permissions) {
        Trace.beginSection("hasPermission");
        try {
            for (String permission : permissions) {
                if (context.checkSelfPermission(permission)
                        != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            }
            return true;
        } finally {
            Trace.endSection();
        }
    }
}
