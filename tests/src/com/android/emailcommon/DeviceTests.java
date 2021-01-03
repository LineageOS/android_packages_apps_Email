/*
 /*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.emailcommon;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import java.io.IOException;

@SmallTest
public class DeviceTests extends AndroidTestCase {

    public void testGetDeviceId() throws IOException {
        // Note null is a valid return value.  But still it should be consistent.
        final String deviceId = Device.getDeviceId(getContext());
        final String deviceId2 = Device.getDeviceId(getContext());
        // Should be consistent.
        assertEquals(deviceId, deviceId2);
    }

}
