/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.email.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.android.email.Preferences;
import com.android.email.R;
import com.android.email.SecurityPolicy;
import com.android.email.activity.setup.AccountSettings;
import com.android.email.provider.AccountReconciler;
import com.android.email.provider.EmailProvider;
import com.android.emailcommon.Logging;
import com.android.emailcommon.VendorPolicyLoader;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;
import com.android.mail.utils.LogUtils;
import android.util.Log;


/**
 * The broadcast receiver.  The actual job is done in EmailBroadcastProcessor on a worker thread.
 */
public class EmailBroadcastReceiver extends BroadcastReceiver {

private static final String ACTION_CHECK_MAIL = "com.android.email.intent.action.MAIL_SERVICE_WAKEUP";
private static final String EXTRA_ACCOUNT = "com.android.email.intent.extra.ACCOUNT";
private static final String TAG = "EmailBroadcastReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG,"Received " + intent.getAction());
        if(ACTION_CHECK_MAIL.equals(intent.getAction())) {
           Intent i;
           final long accountId = intent.getLongExtra(EXTRA_ACCOUNT, -1);
           Log.d(TAG,"accountId is " + accountId);
           final long inboxId = Mailbox.findMailboxOfType(context, accountId,
               Mailbox.TYPE_INBOX);
           Log.d(TAG,"inboxId is " + inboxId);
           Mailbox mailbox = Mailbox.restoreMailboxWithId(context, inboxId);
           if (mailbox == null) return;
           Account account = Account.restoreAccountWithId(context, mailbox.mAccountKey);

           String protocol = account.getProtocol(context);
           Log.d(TAG,"protocol is "+protocol);
           String legacyImapProtocol = context.getString(R.string.protocol_legacy_imap);
           if (protocol.equals(legacyImapProtocol)) {
               i = new Intent(context, ImapService.class);
           } else {
               i = new Intent(context, Pop3Service.class);
           }
           i.setAction(intent.getAction());
           i.putExtra("com.android.email.intent.extra.ACCOUNT", intent.getLongExtra(EXTRA_ACCOUNT, -1));
           context.startService(i);
        } else {
           EmailBroadcastProcessorService.processBroadcastIntent(context, intent);
        }
   }
}
