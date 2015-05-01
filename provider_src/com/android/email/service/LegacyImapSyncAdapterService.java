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

import static com.android.emailcommon.Logging.LOG_TAG;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.content.AbstractThreadedSyncAdapter;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SyncResult;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.text.format.DateUtils;

import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.IEmailService;
import com.android.mail.utils.LogUtils;

public class LegacyImapSyncAdapterService extends PopImapSyncAdapterService {

    // The call to ServiceConnection.onServiceConnected is asynchronous to bindService. It's
    // possible for that to be delayed if, in which case, a call to onPerformSync
    // could occur before we have a connection to the service.
    // In onPerformSync, if we don't yet have our ImapService, we will wait for up to 10
    // seconds for it to appear. If it takes longer than that, we will fail the sync.
    private static final long MAX_WAIT_FOR_SERVICE_MS = 10 * DateUtils.SECOND_IN_MILLIS;

    private static final ExecutorService sExecutor = Executors.newCachedThreadPool();

    private IEmailService mImapService;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name,  IBinder binder) {
            if (Logging.LOGD) {
                LogUtils.v(LOG_TAG, "onServiceConnected");
            }
            synchronized (mConnection) {
                mImapService = IEmailService.Stub.asInterface(binder);
                mConnection.notify();

                // We need to run this task in the background (not in UI-Thread)
                sExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        final Context context = LegacyImapSyncAdapterService.this;
                        ImapService.registerAllImapIdleMailboxes(context, mImapService);
                    }
                });
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mImapService = null;
        }
    };

    protected class ImapSyncAdapterImpl extends SyncAdapterImpl {
        public ImapSyncAdapterImpl(Context context) {
            super(context);
        }

        @Override
        public void onPerformSync(android.accounts.Account account, Bundle extras,
                String authority, ContentProviderClient provider, SyncResult syncResult) {

            final Context context = LegacyImapSyncAdapterService.this;
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "Imap Sync WakeLock");
            try {
                wl.acquire();

                if (!waitForService()) {
                    // The service didn't connect, nothing we can do.
                    return;
                }

                if (!Mailbox.isPushOnlyExtras(extras)) {
                    super.onPerformSync(account, extras, authority, provider, syncResult);
                }

                // Check if IMAP push service is necessary
                ImapService.stopImapPushServiceIfNecessary(context);

            } finally {
                wl.release();
            }
        }
    }

    public AbstractThreadedSyncAdapter getSyncAdapter() {
        return new ImapSyncAdapterImpl(getApplicationContext());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        bindService(new Intent(this, ImapService.class), mConnection, Context.BIND_AUTO_CREATE);
        startService(new Intent(this, LegacyImapSyncAdapterService.class));
    }

    @Override
    public void onDestroy() {
        unbindService(mConnection);
        super.onDestroy();
    }

    private final boolean waitForService() {
        synchronized(mConnection) {
            if (mImapService == null) {
                if (Logging.LOGD) {
                    LogUtils.v(LOG_TAG, "ImapService not yet connected");
                }
                try {
                    mConnection.wait(MAX_WAIT_FOR_SERVICE_MS);
                } catch (InterruptedException e) {
                    LogUtils.wtf(LOG_TAG, "InterrupedException waiting for ImapService to connect");
                    return false;
                }
                if (mImapService == null) {
                    LogUtils.wtf(LOG_TAG, "timed out waiting for ImapService to connect");
                    return false;
                }
            }
        }
        return true;
    }

}
