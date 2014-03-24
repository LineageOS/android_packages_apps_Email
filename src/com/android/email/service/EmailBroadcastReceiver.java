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

import com.android.email.R;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.Mailbox;
import com.android.mail.utils.LogUtils;

/**
 * The broadcast receiver. The actual job is done in EmailBroadcastProcessor on a worker thread.
 */
public class EmailBroadcastReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        EmailBroadcastProcessorService.processBroadcastIntent(context, intent);
    }

    public static class ServiceWakeupReceiver extends BroadcastReceiver {
        private static final String TAG = "ServiceWakeupReceiver";
        private static final String EXTRA_ACCOUNT = "com.android.email.intent.extra.ACCOUNT";
        private static final String ACTION_CHECK_MAIL =
                "com.android.email.intent.action.MAIL_SERVICE_WAKEUP";

        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_CHECK_MAIL.equals(intent.getAction())) {
                final long accountId = intent.getLongExtra(EXTRA_ACCOUNT, -1);
                final long inboxId = Mailbox.findMailboxOfType(context, accountId,
                        Mailbox.TYPE_INBOX);
                final Mailbox mailbox = Mailbox.restoreMailboxWithId(context, inboxId);
                LogUtils.d(TAG, "account id is: " + accountId + ", inbox id is: " + inboxId);
                if (mailbox == null) {
                    LogUtils.w(TAG, "Inbox do not exist, do nothing.");
                    return;
                }

                Account account = Account.restoreAccountWithId(context, accountId);
                String protocol = account.getProtocol(context);
                LogUtils.d(TAG, "protocol is " + protocol);

                Intent i;
                String legacyImapProtocol = context.getString(R.string.protocol_legacy_imap);
                String pop3Protocol = context.getString(R.string.protocol_pop3);
                if (legacyImapProtocol.equals(protocol)) {
                    i = new Intent(context, ImapService.class);
                } else if (pop3Protocol.equals(protocol)) {
                    i = new Intent(context, Pop3Service.class);
                } else {
                    // Do not support the Exchange account now.
                    return;
                }
                i.setAction(intent.getAction());
                i.putExtra("com.android.email.intent.extra.ACCOUNT", accountId);
                context.startService(i);
            }
        }
    }
}
