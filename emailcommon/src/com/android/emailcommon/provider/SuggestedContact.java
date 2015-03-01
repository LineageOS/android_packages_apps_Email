/*
 * Copyright (C) 2014 The CyanogenMod Project
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


package com.android.emailcommon.provider;

import android.net.Uri;
import android.provider.BaseColumns;

import com.android.emailcommon.provider.EmailContent.SuggestedContactColumns;

/**
 * A suggested contact extracted from sent and received emails to be displayed when the user
 * compose a message. Tied to a specific account.
 */
public abstract class SuggestedContact extends EmailContent
        implements SuggestedContactColumns {
    public static final String TABLE_NAME = "SuggestedContact";
    public static Uri CONTENT_URI;
    public static Uri ACCOUNT_ID_URI;

    public static final String[] PROJECTION = new String[] {
            SuggestedContact._ID,
            SuggestedContact.ACCOUNT_KEY,
            SuggestedContact.ADDRESS,
            SuggestedContact.NAME,
            SuggestedContact.DISPLAY_NAME,
            SuggestedContact.LAST_SEEN,
    };

    public static void initSuggestedContact() {
        CONTENT_URI = Uri.parse(EmailContent.CONTENT_URI + "/suggestedcontact");
        ACCOUNT_ID_URI = Uri.parse(EmailContent.CONTENT_URI + "/suggestedcontact/account");
    }
}