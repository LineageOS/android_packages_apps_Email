/**
 * Copyright (c) 2018 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.email.activity;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.support.v7.app.AppCompatActivity;

public class BatteryOptimizationsExemptionProxyActivity extends AppCompatActivity {
    private static final int REQUEST_EXEMPTION = 1;

    public static Intent createIntent(Context context, Intent successServiceIntent) {
        return new Intent(context, BatteryOptimizationsExemptionProxyActivity.class)
            .putExtra("serviceIntent", successServiceIntent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent requestIntent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:" + getPackageName()));
        startActivityForResult(requestIntent, REQUEST_EXEMPTION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_EXEMPTION && resultCode == RESULT_OK) {
            Intent serviceIntent = getIntent().getParcelableExtra("serviceIntent");
            if (serviceIntent != null) {
                startService(serviceIntent);
            }
        }
        finish();
    }
}
