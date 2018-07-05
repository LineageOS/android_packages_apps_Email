/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.BaseColumns;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.SparseArray;
import android.util.SparseLongArray;

import com.android.email.DebugUtils;
import com.android.email.EmailConnectivityManager;
import com.android.email.LegacyConversions;
import com.android.email.NotificationController;
import com.android.email.NotificationControllerCreatorHolder;
import com.android.email.R;
import com.android.email.mail.Store;
import com.android.email.mail.store.ImapFolder;
import com.android.email.provider.EmailProvider;
import com.android.email.provider.Utilities;
import com.android.emailcommon.Logging;

import static com.android.emailcommon.Logging.LOG_TAG;

import com.android.emailcommon.TrafficFlags;
import com.android.emailcommon.internet.MimeUtility;
import com.android.emailcommon.mail.AuthenticationFailedException;
import com.android.emailcommon.mail.FetchProfile;
import com.android.emailcommon.mail.Flag;
import com.android.emailcommon.mail.Folder;
import com.android.emailcommon.mail.Folder.FolderType;
import com.android.emailcommon.mail.Folder.MessageRetrievalListener;
import com.android.emailcommon.mail.Folder.MessageUpdateCallbacks;
import com.android.emailcommon.mail.Folder.OpenMode;
import com.android.emailcommon.mail.Message;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.mail.Part;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.emailcommon.provider.EmailContent.SyncColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.EmailServiceStatus;
import com.android.emailcommon.service.IEmailService;
import com.android.emailcommon.service.SearchParams;
import com.android.emailcommon.service.SyncWindow;
import com.android.emailcommon.utility.AttachmentUtilities;
import com.android.mail.providers.UIProvider;
import com.android.mail.utils.LogUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ImapService extends Service {
    // TODO get these from configurations or settings.
    private static final long QUICK_SYNC_WINDOW_MILLIS = DateUtils.DAY_IN_MILLIS;
    private static final long FULL_SYNC_INTERVAL_MILLIS = 4 * DateUtils.HOUR_IN_MILLIS;

    // The maximum number of messages to fetch in a single command.
    private static final int MAX_MESSAGES_TO_FETCH = 500;
    private static final int MINIMUM_MESSAGES_TO_SYNC = 10;
    private static final int LOAD_MORE_MIN_INCREMENT = 10;
    private static final int LOAD_MORE_MAX_INCREMENT = 20;
    private static final long INITIAL_WINDOW_SIZE_INCREASE = 24 * 60 * 60 * 1000;

    private static final Flag[] FLAG_LIST_SEEN = new Flag[] { Flag.SEEN };
    private static final Flag[] FLAG_LIST_FLAGGED = new Flag[] { Flag.FLAGGED };
    private static final Flag[] FLAG_LIST_ANSWERED = new Flag[] { Flag.ANSWERED };

    // Kick idle connection every ~25 minutes (in a window between 25 and 28 minutes)
    private static final int KICK_IDLE_CONNECTION_TIMEOUT = 25 * 60 * 1000;
    private static final int KICK_IDLE_CONNECTION_MAX_DELAY = 3 * 60 * 1000;

    // Restart idle connection between 30 seconds and 1 minute after re-gaining connectivity
    private static final int RESTART_IDLE_DELAY_MIN = 30 * 1000;
    private static final int RESTART_IDLE_DELAY_MAX = 60 * 1000;

    /**
     * Simple cache for last search result mailbox by account and serverId, since the most common
     * case will be repeated use of the same mailbox
     */
    private static long mLastSearchAccountKey = Account.NO_ACCOUNT;
    private static String mLastSearchServerId = null;
    private static Mailbox mLastSearchRemoteMailbox = null;

    /**
     * Cache search results by account; this allows for "load more" support without having to
     * redo the search (which can be quite slow).  SortableMessage is a smallish class, so memory
     * shouldn't be an issue
     */
    private static final HashMap<Long, SortableMessage[]> sSearchResults =
            new HashMap<Long, SortableMessage[]>();

    private static final ExecutorService sExecutor = Executors.newCachedThreadPool();

    /**
     * We write this into the serverId field of messages that will never be upsynced.
     */
    private static final String LOCAL_SERVERID_PREFIX = "Local-";
    private static final String ACTION_RESTART_IDLE_CONNECTION =
            "com.android.email.intent.action.RESTART_IDLE_CONNECTION";
    private static final String ACTION_RESTART_ALL_IDLE_CONNECTIONS =
            "com.android.email.intent.action.RESTART_ALL_IDLE_CONNECTIONS";
    private static final String ACTION_KICK_IDLE_CONNECTION =
            "com.android.email.intent.action.KICK_IDLE_CONNECTION";
    private static final String EXTRA_MAILBOX = "com.android.email.intent.extra.MAILBOX";

    private static final long RESCHEDULE_PING_DELAY = 500L;
    private static final long MAX_PING_DELAY = 30 * 60 * 1000L;

    private static String sLegacyImapProtocol;

    private static String sMessageDecodeErrorString;

    private static boolean mSyncLock;

    /**
     * Used in ImapFolder for base64 errors. Cached here because ImapFolder does not have access
     * to a Context object.
     *
     * @return Error string or empty string
     */
    public static String getMessageDecodeErrorString() {
        return sMessageDecodeErrorString == null ? "" : sMessageDecodeErrorString;
    }

    private static class ImapIdleListener implements ImapFolder.IdleCallback {
        private static final SparseLongArray sPingDelay = new SparseLongArray();
        private final Context mContext;

        private final Account mAccount;
        private final Mailbox mMailbox;

        public ImapIdleListener(Context context, Account account, Mailbox mailbox) {
            super();
            mContext = context;
            mAccount = account;
            mMailbox = mailbox;
        }

        @Override
        public void onIdled() {
            scheduleKickIdleConnection();
        }

        @Override
        public void onIdlingDone() {
            cancelKickIdleConnection();
            cancelPing();
            resetPingDelay();
        }

        @Override
        public void onNewServerChange(final boolean needSync, final List<String> fetchMessages) {
            // Instead of checking every received change, request a sync of the mailbox
            if (Logging.LOGD) {
                LogUtils.d(LOG_TAG, "Server notified new changes for mailbox " + mMailbox.mId);
            }
            cancelKickIdleConnection();
            cancelPing();
            resetPingDelay();

            // Request a sync but wait a bit for new incoming messages from server
            sExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    // Selectively process all the retrieved changes
                    processImapIdleChangesLocked(mContext, mAccount, mMailbox,
                            needSync, fetchMessages);
                }
            });
        }

        @Override
        public void onTimeout() {
            // Timeout reschedule a new ping
            LogUtils.i(LOG_TAG, "Ping timeout for mailbox " + mMailbox.mId + ". Reschedule.");
            cancelKickIdleConnection();
            internalUnregisterFolderIdle();
            reschedulePing(RESCHEDULE_PING_DELAY);
            resetPingDelay();
        }

        @Override
        public void onException(MessagingException ex) {
            // Reschedule a new ping
            LogUtils.e(LOG_TAG, ex, "Ping exception for mailbox " + mMailbox.mId);
            cancelKickIdleConnection();
            internalUnregisterFolderIdle();
            reschedulePing(increasePingDelay());
        }

        private void internalUnregisterFolderIdle() {
            ImapIdleFolderHolder holder = ImapIdleFolderHolder.getInstance();
            synchronized (holder.mIdledFolders) {
                holder.mIdledFolders.remove((int) mMailbox.mId);
            }
        }

        private void reschedulePing(long delay) {
            // Check for connectivity before reschedule
            boolean connected = EmailConnectivityManager.isConnected(mContext);
            if (Logging.LOGD) {
                LogUtils.d(LOG_TAG, "Rescheduling ping for mailbox " + mMailbox.mId
                        + ", delay " + delay + "ms, connected " + connected);
            }
            if (!connected) {
                cancelPing();
                ImapService.checkBatteryOptimizationsExemption(mContext);
            } else {
                PendingIntent pi = getIdleRefreshIntent();
                AlarmManager am = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
                am.set(AlarmManager.RTC_WAKEUP, delay, pi);
            }
        }

        private void cancelPing() {
            AlarmManager am = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
            am.cancel(getIdleRefreshIntent());
        }

        private void resetPingDelay() {
            synchronized (sPingDelay) {
                int index = sPingDelay.indexOfKey((int) mMailbox.mId);
                if (index >= 0) {
                    sPingDelay.removeAt(index);
                }
            }
        }

        private long increasePingDelay() {
            synchronized (sPingDelay) {
                long delay = Math.max(RESCHEDULE_PING_DELAY, sPingDelay.get((int) mMailbox.mId));
                delay = Math.min(MAX_PING_DELAY, delay * 2);
                sPingDelay.put((int) mMailbox.mId, delay);
                return delay;
            }
        }

        private PendingIntent getIdleRefreshIntent() {
            Intent i = new Intent(mContext, ImapService.class);
            i.setAction(ACTION_RESTART_IDLE_CONNECTION);
            i.putExtra(EXTRA_MAILBOX, mMailbox.mId);
            return PendingIntent.getService(mContext, (int) mMailbox.mId, i,
                    PendingIntent.FLAG_UPDATE_CURRENT);
        }

        private void scheduleKickIdleConnection() {
            PendingIntent pi = getKickIdleConnectionPendingIntent();
            long due = SystemClock.elapsedRealtime() + KICK_IDLE_CONNECTION_TIMEOUT;
            long windowLength = KICK_IDLE_CONNECTION_MAX_DELAY;
            LogUtils.d(LOG_TAG, "Scheduling IDLE connection kick for mailbox " + mMailbox.mId);
            AlarmManager am = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
            am.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, due, windowLength, pi);
        }

        private void cancelKickIdleConnection() {
            AlarmManager am = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
            LogUtils.d(LOG_TAG, "Canceling IDLE connection kick for mailbox " + mMailbox.mId);
            am.cancel(getKickIdleConnectionPendingIntent());
        }

        private PendingIntent getKickIdleConnectionPendingIntent() {
            Intent i = new Intent(mContext, ImapService.class);
            i.setAction(ACTION_KICK_IDLE_CONNECTION);
            i.putExtra(EXTRA_MAILBOX, mMailbox.mId);
            return PendingIntent.getService(mContext, (int) mMailbox.mId,
                    i, PendingIntent.FLAG_UPDATE_CURRENT);
        }
    }

    private static class ImapIdleFolderHolder {
        private static ImapIdleFolderHolder sInstance;
        private SparseArray<ImapFolder> mIdledFolders = new SparseArray<>();

        private static ImapIdleFolderHolder getInstance() {
            if (sInstance == null) {
                sInstance = new ImapIdleFolderHolder();
            }
            return sInstance;
        }

        private ImapFolder getIdledMailbox(long mailboxId) {
            synchronized (mIdledFolders) {
                ImapFolder folder = mIdledFolders.get((int) mailboxId);
                return folder != null && folder.isIdling() ? folder : null;
            }
        }

        private boolean isMailboxIdled(long mailboxId) {
            return getIdledMailbox(mailboxId) != null;
        }

        private boolean registerMailboxForIdle(Context context, Account account, Mailbox mailbox)
                throws MessagingException {
            synchronized (mIdledFolders) {
                if (mailbox.mType == Mailbox.TYPE_DRAFTS || mailbox.mType == Mailbox.TYPE_OUTBOX) {
                    LogUtils.i(LOG_TAG, "Mailbox is not a valid idle folder: " + mailbox.mId);
                    return false;
                }

                // Check that the account is ready for push
                if (account.getSyncInterval() != Account.CHECK_INTERVAL_PUSH) {
                    LogUtils.d(LOG_TAG, "Account is not configured as push: " + account.mId);
                    return false;
                }

                // Check that the folder isn't already registered
                if (isMailboxIdled(mailbox.mId)) {
                    LogUtils.i(LOG_TAG, "Mailbox is idled already: " + mailbox.mId);
                    return true;
                }

                if (!EmailConnectivityManager.isConnected(context)) {
                    LogUtils.i(LOG_TAG, "No available connection to register "
                            + "mailbox for idle: " + mailbox.mId);
                    return false;
                }

                // And now just idle the folder
                try {
                    Store remoteStore = Store.getInstance(account, context);
                    ImapFolder folder = mIdledFolders.get((int) mailbox.mId);
                    if (folder == null) {
                        folder = (ImapFolder) remoteStore.getFolder(mailbox.mServerId);
                        mIdledFolders.put((int) mailbox.mId, folder);
                    }
                    folder.open(OpenMode.READ_WRITE);
                    folder.startIdling(new ImapIdleListener(context,
                            remoteStore.getAccount(), mailbox));

                    LogUtils.i(LOG_TAG, "Registered idle for mailbox " + mailbox.mId);
                    return true;
                } catch (Exception ex) {
                    LogUtils.i(LOG_TAG, ex, "Failed to register idle for mailbox " + mailbox.mId);
                }
                return false;
            }
        }

        private void unregisterIdledMailbox(long mailboxId, boolean remove)
                throws MessagingException {
            final ImapFolder folder;
            synchronized (mIdledFolders) {
                folder = unregisterIdledMailboxLocked(mailboxId, remove);
            }
            if (folder != null) {
                folder.stopIdling(remove);
            }
        }

        private ImapFolder unregisterIdledMailboxLocked(long mailboxId, boolean remove)
                throws MessagingException {
            // Check that the folder is already registered
            ImapFolder folder = mIdledFolders.get((int) mailboxId);
            if (folder == null || !folder.isIdling()) {
                LogUtils.i(LOG_TAG, "Mailbox isn't idled yet: " + mailboxId);
                return null;
            }

            if (remove) {
                mIdledFolders.remove((int) mailboxId);
            }

            LogUtils.i(LOG_TAG, "Unregistered idle for mailbox " + mailboxId);
            return folder;
        }

        private void registerAccountForIdle(Context context, Account account)
                throws MessagingException {
            // Check that the account is ready for push
            if (account.getSyncInterval() != Account.CHECK_INTERVAL_PUSH) {
                LogUtils.d(LOG_TAG, "Account is not configured as push: " + account.mId);
                return;
            }

            LogUtils.i(LOG_TAG, "Register idle for account " + account.mId);
            Cursor c = Mailbox.getLoopBackMailboxIdsForSync(
                    context.getContentResolver(), account.mId);
            if (c != null) {
                try {
                    boolean hasSyncMailboxes = false;
                    while (c.moveToNext()) {
                        long mailboxId = c.getLong(c.getColumnIndex(BaseColumns._ID));
                        final Mailbox mailbox = Mailbox.restoreMailboxWithId(context, mailboxId);
                        boolean registered = isMailboxIdled(mailboxId);
                        if (!registered) {
                            registered = registerMailboxForIdle(context, account, mailbox);
                        }
                        hasSyncMailboxes |= registered;
                    }

                    // Sync the inbox
                    if (!hasSyncMailboxes) {
                        final long inboxId = Mailbox.findMailboxOfType(
                                context, account.mId, Mailbox.TYPE_INBOX);
                        if (inboxId != Mailbox.NO_MAILBOX) {
                            final Mailbox inbox = Mailbox.restoreMailboxWithId(context, inboxId);
                            if (!isMailboxIdled(inbox.mId)) {;
                                registerMailboxForIdle(context, account, inbox);
                            }
                        }
                    }
                } finally {
                    c.close();
                }
            }
        }

        private void kickAccountIdledMailboxes(Context context, Account account)
                throws MessagingException {
            synchronized (mIdledFolders) {
                unregisterAccountIdledMailboxes(context, account.mId, true);
                registerAccountForIdle(context, account);
            }
        }

        private void kickIdledMailbox(Context context, Mailbox mailbox, Account account)
                throws MessagingException {
            final ImapFolder folder = getIdledMailbox((int) mailbox.mId);
            if (folder != null) {
                folder.stopIdling(false);
                folder.startIdling(new ImapIdleListener(context, account, mailbox));
            }
        }

        private void unregisterAccountIdledMailboxes(Context context, long accountId,
                boolean remove) {
            LogUtils.i(LOG_TAG, "Unregister idle for account " + accountId);
            final ArrayList<ImapFolder> foldersToStop = new ArrayList<>();

            synchronized (mIdledFolders) {
                int count = mIdledFolders.size() - 1;
                for (int index = count; index >= 0; index--) {
                    long mailboxId = mIdledFolders.keyAt(index);
                    try {
                        Mailbox mailbox = Mailbox.restoreMailboxWithId(context, mailboxId);
                        if (mailbox == null || mailbox.mAccountKey == accountId) {
                            ImapFolder folder = unregisterIdledMailboxLocked(mailboxId, remove);
                            if (folder != null) {
                                foldersToStop.add(folder);
                                LogUtils.i(LOG_TAG, "Unregister idle for mailbox " + mailboxId);
                            }
                        }
                    } catch (MessagingException ex) {
                        LogUtils.w(LOG_TAG, "Failed to unregister mailbox "
                                + mailboxId + " for account " + accountId);
                    }
                }
            }
            stopIdlingForFoldersInBackground(foldersToStop);
        }

        private void unregisterAllIdledMailboxes(final boolean disconnect) {
            final ArrayList<ImapFolder> foldersToStop = new ArrayList<>();
            synchronized (mIdledFolders) {
                LogUtils.i(LOG_TAG, "Unregister all idle mailboxes");

                if (disconnect) {
                    int count = mIdledFolders.size();
                    for (int i = 0; i < count; i++) {
                        ImapFolder folder = mIdledFolders.get(mIdledFolders.keyAt(i));
                        if (folder != null && folder.isIdling()) {
                            foldersToStop.add(folder);
                        }
                    }
                }
                mIdledFolders.clear();
            }
            stopIdlingForFoldersInBackground(foldersToStop);
        }

        private void stopIdlingForFoldersInBackground(final List<ImapFolder> folders) {
            if (folders.isEmpty()) {
                return;
            }
            sExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    for (ImapFolder folder : folders) {
                        try {
                            folder.stopIdling(true);
                        } catch (MessagingException me) {
                            // ignored
                        }
                    }
                }
            });
        }
    }

    private static class ImapEmailConnectivityManager extends EmailConnectivityManager {
        private final Context mContext;

        public ImapEmailConnectivityManager(Context context) {
            super(context, LOG_TAG);
            mContext = context;
        }

        public void destroy() {
            cancelIdleConnectionRestart();
        }

        @Override
        public void onConnectivityRestored(int networkType) {
            if (Logging.LOGD) {
                LogUtils.d(Logging.LOG_TAG, "onConnectivityRestored ("
                        + "networkType=" + networkType + ")");
            }

            scheduleIdleConnectionRestart();
        }

        @Override
        public void onConnectivityLost(int networkType) {
            if (Logging.LOGD) {
                LogUtils.d(Logging.LOG_TAG, "onConnectivityLost ("
                        + "networkType=" + networkType + ")");
            }
            // Only remove references. We have no network to kill idled connections
            ImapIdleFolderHolder.getInstance().unregisterAllIdledMailboxes(false);
            cancelIdleConnectionRestart();
        }

        private void scheduleIdleConnectionRestart() {
            PendingIntent pi = getIdleConnectionRestartIntent();
            long due = SystemClock.elapsedRealtime() + RESTART_IDLE_DELAY_MIN;
            long windowLength = RESTART_IDLE_DELAY_MAX - RESTART_IDLE_DELAY_MIN;
            AlarmManager am = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
            am.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, due, windowLength, pi);
        }

        private void cancelIdleConnectionRestart() {
            AlarmManager am = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
            am.cancel(getIdleConnectionRestartIntent());
        }

        private PendingIntent getIdleConnectionRestartIntent() {
            Intent i = new Intent(mContext, ImapService.class);
            i.setAction(ACTION_RESTART_ALL_IDLE_CONNECTIONS);
            return PendingIntent.getService(mContext, 0, i, 0);
        }
    }

    private static class LocalChangesContentObserver extends ContentObserver {
        private Context mContext;

        public LocalChangesContentObserver(Context context, Handler handler) {
            super(handler);
            mContext = context;
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            // what changed?
            try {
                List<String> segments = uri.getPathSegments();
                final String type = segments.get(0);
                final String op = segments.get(1);
                final long id = Long.parseLong(uri.getLastPathSegment());

                // Run the changes processor outside the ui thread
                sExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        // Apply the change
                        if (type.equals("account")) {
                            processAccountChanged(op, id);
                        } else if (type.equals("mailbox")) {
                            processMailboxChanged(op, id);
                        } else if (type.equals("message")) {
                            processMessageChanged(op, id);
                        }
                    }
                });
            } catch (Exception ex) {
                return;
            }
        }

        private void processAccountChanged(String op, long id) {
            // For delete operations we can't fetch the account, so process it first
            if (op.equals(EmailProvider.NOTIFICATION_OP_DELETE)) {
                ImapIdleFolderHolder.getInstance()
                        .unregisterAccountIdledMailboxes(mContext, id, true);
                stopImapPushServiceIfNecessary(mContext);
                return;
            }

            Account account = Account.restoreAccountWithId(mContext, id);
            if (account == null) {
                return;
            }
            if (!isLegacyImapProtocol(mContext, account)) {
                // The account isn't an imap account
                return;
            }

            try {
                final ImapIdleFolderHolder holder = ImapIdleFolderHolder.getInstance();
                if (op.equals(EmailProvider.NOTIFICATION_OP_UPDATE)) {
                    holder.kickAccountIdledMailboxes(mContext, account);
                } else if (op.equals(EmailProvider.NOTIFICATION_OP_INSERT)) {
                    if (account.getSyncInterval() == Account.CHECK_INTERVAL_PUSH) {
                        holder.registerAccountForIdle(mContext, account);
                    }
                }
            } catch (MessagingException me) {
                LogUtils.e(LOG_TAG, me, "Failed to process imap account " + id + " changes.");
            }

            // Check if service should be started/stopped
            stopImapPushServiceIfNecessary(mContext);
        }

        private void processMailboxChanged(String op, long id) {
            // For delete operations we can't fetch the mailbox, so process it first
            if (op.equals(EmailProvider.NOTIFICATION_OP_DELETE)) {
                try {
                    ImapIdleFolderHolder.getInstance().unregisterIdledMailbox(id, true);
                } catch (MessagingException me) {
                    LogUtils.e(LOG_TAG, me, "Failed to process imap mailbox " + id + " changes.");
                }
                return;
            }

            Mailbox mailbox = Mailbox.restoreMailboxWithId(mContext, id);
            if (mailbox == null) {
                return;
            }
            Account account = Account.restoreAccountWithId(mContext, mailbox.mAccountKey);
            if (account == null) {
                return;
            }
            if (!isLegacyImapProtocol(mContext, account)) {
                // The account isn't an imap account
                return;
            }

            try {
                final ImapIdleFolderHolder holder = ImapIdleFolderHolder.getInstance();
                if (op.equals(EmailProvider.NOTIFICATION_OP_UPDATE)) {
                    // Only apply if syncInterval has changed
                    boolean registered = holder.isMailboxIdled(id);
                    boolean toRegister = mailbox.mSyncInterval == 1
                            && account.getSyncInterval() == Account.CHECK_INTERVAL_PUSH;
                    if (registered != toRegister) {
                        if (registered) {
                            holder.unregisterIdledMailbox(id, true);
                        }
                        if (toRegister) {
                            holder.registerMailboxForIdle(mContext, account, mailbox);
                        }
                    }
                } else if (op.equals(EmailProvider.NOTIFICATION_OP_INSERT)) {
                    if (account.getSyncInterval() == Account.CHECK_INTERVAL_PUSH) {
                        holder.registerMailboxForIdle(mContext, account, mailbox);
                    }
                }
            } catch (MessagingException me) {
                LogUtils.e(LOG_TAG, me, "Failed to process imap mailbox " + id + " changes.");
            }
        }

        private void processMessageChanged(String op, long id) {
            if (mSyncLock) {
                return;
            }
            EmailContent.Message msg = EmailContent.Message.restoreMessageWithId(mContext, id);
            if (msg == null) {
                return;
            }
            Account account = Account.restoreAccountWithId(mContext, msg.mAccountKey);
            if (account == null) {
                return;
            }
            if (!isLegacyImapProtocol(mContext, account)) {
                // The account isn't an imap account
                return;
            }
            if (account.getSyncInterval() != Account.CHECK_INTERVAL_PUSH) {
                return;
            }

            try {
                Store remoteStore = Store.getInstance(account, mContext);
                processPendingActionsSynchronous(mContext, account, remoteStore, false);
            } catch (MessagingException me) {
                LogUtils.e(LOG_TAG, me, "Failed to process imap message " + id + " changes.");
            }
        }
    }

    private ImapEmailConnectivityManager mConnectivityManager;
    private LocalChangesContentObserver mLocalChangesObserver;
    private Handler mServiceHandler;
    private PowerManager.WakeLock mIdleRefreshWakeLock;

    @Override
    public void onCreate() {
        super.onCreate();

        sMessageDecodeErrorString = getString(R.string.message_decode_error);
        mServiceHandler = new Handler();

        // Initialize the email provider and the listeners/observers
        EmailContent.init(this);
        mConnectivityManager = new ImapEmailConnectivityManager(this);
        mLocalChangesObserver = new LocalChangesContentObserver(this, mServiceHandler);

        // Initialize wake locks
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mIdleRefreshWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Imap IDLE Refresh");
        mIdleRefreshWakeLock.setReferenceCounted(true);

        // Register observers
        getContentResolver().registerContentObserver(
                Account.SYNC_SETTING_CHANGED_URI, true, mLocalChangesObserver);
        getContentResolver().registerContentObserver(
                Mailbox.SYNC_SETTING_CHANGED_URI, true, mLocalChangesObserver);
        getContentResolver().registerContentObserver(
                EmailContent.Message.NOTIFIER_URI, true, mLocalChangesObserver);
    }

    @Override
    public void onDestroy() {
        // Unregister services
        ImapIdleFolderHolder.getInstance().unregisterAllIdledMailboxes(true);
        mConnectivityManager.unregister();
        getContentResolver().unregisterContentObserver(mLocalChangesObserver);
        mConnectivityManager.destroy();

        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return Service.START_STICKY;
        }

        final String action = intent.getAction();
        if (Logging.LOGD) {
            LogUtils.d(Logging.LOG_TAG, "Action: ", action);
        }
        final Context context = getApplicationContext();

        if (ACTION_RESTART_ALL_IDLE_CONNECTIONS.equals(action)) {
            // Initiate a sync for all IDLEd accounts, since there might have
            // been changes while we lost connectivity. At the end of the sync
            // the IDLE connection will be re-established.

            mIdleRefreshWakeLock.acquire();

            sExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    ContentResolver cr = context.getContentResolver();
                    List<Account> syncAccounts = new ArrayList<>();
                    Cursor c = null;
                    try {
                        c = cr.query(Account.CONTENT_URI, Account.CONTENT_PROJECTION,
                                null, null, null);
                        if (c == null) {
                            return;
                        }
                        while (c.moveToNext()) {
                            final Account account = new Account();
                            account.restore(c);

                            // Only imap push accounts
                            if (account.getSyncInterval() == Account.CHECK_INTERVAL_PUSH
                                    && isLegacyImapProtocol(context, account)) {
                                syncAccounts.add(account);
                            }
                        }
                        if (!syncAccounts.isEmpty()
                                && checkBatteryOptimizationsExemption(context)) {
                            for (Account account : syncAccounts) {
                                requestSyncForAccountMailboxesIfNotIdled(account);
                            }
                        }
                    } finally {
                        if (c != null) {
                            c.close();
                        }
                        mIdleRefreshWakeLock.release();
                    }
                }
            });
        } else if (ACTION_RESTART_IDLE_CONNECTION.equals(action)) {
            final long mailboxId = intent.getLongExtra(EXTRA_MAILBOX, -1);
            if (mailboxId < 0) {
                 return START_NOT_STICKY;
            }

            mIdleRefreshWakeLock.acquire();

            sExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        // Check that the account is ready for push
                        Mailbox mailbox = Mailbox.restoreMailboxWithId(context, mailboxId);
                        if (mailbox == null) {
                            return;
                        }
                        Account account = Account.restoreAccountWithId(context,
                                mailbox.mAccountKey);
                        if (account == null) {
                            return;
                        }

                        if (account.getSyncInterval() != Account.CHECK_INTERVAL_PUSH) {
                            LogUtils.i(LOG_TAG, "Account isn't declared for push: " + account.mId);
                            return;
                        }

                        // Request a quick sync to make sure we didn't lose any new mails
                        // during the failure time; IDLE will restart afterwards
                        ImapService.requestSync(context, account, mailboxId, false);
                        LogUtils.d(LOG_TAG, "requestSync after reschedulePing for account %s (%s)",
                                account.toString(), mailbox.mDisplayName);
                    } finally {
                        mIdleRefreshWakeLock.release();
                    }
                }
            });
        } else if (ACTION_KICK_IDLE_CONNECTION.equals(action)) {
            final long mailboxId = intent.getLongExtra(EXTRA_MAILBOX, -1);
            if (mailboxId <= -1) {
                 return START_NOT_STICKY;
            }

            mIdleRefreshWakeLock.acquire();

            sExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        Mailbox mailbox = Mailbox.restoreMailboxWithId(context, mailboxId);
                        if (mailbox == null) {
                            return;
                        }
                        Account account = Account.restoreAccountWithId(context,
                                mailbox.mAccountKey);
                        if (account == null) {
                            return;
                        }

                        ImapIdleFolderHolder holder = ImapIdleFolderHolder.getInstance();
                        holder.kickIdledMailbox(context, mailbox, account);
                    } catch (Exception e) {
                       LogUtils.e(Logging.LOG_TAG, e, "Failed to kick idled connection "
                               + "for mailbox " + mailboxId);
                    } finally {
                        mIdleRefreshWakeLock.release();
                    }
                }
            });
        }

        return Service.START_STICKY;
    }

    private static boolean checkBatteryOptimizationsExemption(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        final NotificationController nc =
                NotificationControllerCreatorHolder.getInstance(context);
        if (pm.isIgnoringBatteryOptimizations(context.getPackageName())) {
            nc.cancelIgnoreBatteryOptimizationsNotification();
            return true;
        }

        Intent intent = new Intent(context, ImapService.class)
                .setAction(ACTION_RESTART_ALL_IDLE_CONNECTIONS);
        nc.showIgnoreBatteryOptimizationsNotification(
                R.string.battery_optimization_notification_imap_text, intent);
        return false;
    }

    private void requestSyncForAccountMailboxesIfNotIdled(Account account) {
        Cursor c = Mailbox.getLoopBackMailboxIdsForSync(getContentResolver(), account.mId);
        if (c == null) {
            return;
        }

        ImapIdleFolderHolder holder = ImapIdleFolderHolder.getInstance();
        try {
            while (c.moveToNext()) {
                long mailboxId = c.getLong(c.getColumnIndex(BaseColumns._ID));
                if (!holder.isMailboxIdled(mailboxId)) {
                    final Mailbox mailbox = Mailbox.restoreMailboxWithId(this, mailboxId);
                    requestSync(this, account, mailboxId, false);
                    LogUtils.d(LOG_TAG, "requestSync after restarting IDLE for account %s (%s)",
                            account.toString(), mailbox.mDisplayName);
                }
            }
        } finally {
            c.close();
        }
    }

    /**
     * Create our EmailService implementation here.
     */
    private final EmailServiceStub mBinder = new EmailServiceStub() {
        @Override
        public int searchMessages(long accountId, SearchParams searchParams, long destMailboxId) {
            try {
                return searchMailboxImpl(getApplicationContext(), accountId, searchParams,
                        destMailboxId);
            } catch (MessagingException e) {
                // Ignore
            }
            return 0;
        }

        @Override
        public void pushModify(long accountId) throws RemoteException {
            final Context context = ImapService.this;
            final Account account = Account.restoreAccountWithId(context, accountId);
            if (account.getSyncInterval() != Account.CHECK_INTERVAL_PUSH) {
                LogUtils.i(LOG_TAG,"Idle (pushModify) isn't avaliable for account " + accountId);
                ImapIdleFolderHolder holder = ImapIdleFolderHolder.getInstance();
                holder.unregisterAccountIdledMailboxes(context, account.mId, true);
                return;
            }

            LogUtils.i(LOG_TAG,"Register idle (pushModify) account " + accountId);
            try {
                ImapIdleFolderHolder holder = ImapIdleFolderHolder.getInstance();
                holder.registerAccountForIdle(context, account);
            } catch (MessagingException ex) {
                LogUtils.d(LOG_TAG, "Failed to modify push for account " + accountId);
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        mBinder.init(this);
        return mBinder;
    }

    private static void requestSync(Context context, Account account, long mailbox, boolean full) {
        final EmailServiceUtils.EmailServiceInfo info = EmailServiceUtils.getServiceInfoForAccount(
                context, account.mId);
        final android.accounts.Account acct = new android.accounts.Account(
                account.mEmailAddress, info.accountType);
        Bundle extras = null;
        if (mailbox != Mailbox.NO_MAILBOX) {
            extras = Mailbox.createSyncBundle(mailbox);
        } else {
            extras = new Bundle();
        }
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, false);
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_DO_NOT_RETRY, true);
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, full);
        ContentResolver.requestSync(acct, EmailContent.AUTHORITY, extras);
    }

    protected static final void stopImapPushServiceIfNecessary(Context context) {
        ContentResolver cr = context.getContentResolver();
        Cursor c = cr.query(Account.CONTENT_URI, Account.CONTENT_PROJECTION,null, null, null);
        if (c != null) {
            try {
                while (c.moveToNext()) {
                    final Account account = new Account();
                    account.restore(c);

                    // Only imap push accounts
                    if (account.getSyncInterval() != Account.CHECK_INTERVAL_PUSH ||
                            !ImapService.isLegacyImapProtocol(context, account)) {
                        continue;
                    }

                    return;
                }
            } finally {
                c.close();
            }
        }

        // Stop the service
        context.stopService(new Intent(context, LegacyImapSyncAdapterService.class));
    }

    public static boolean isLegacyImapProtocol(Context ctx, Account acct) {
        if (sLegacyImapProtocol == null) {
            sLegacyImapProtocol = ctx.getString(R.string.protocol_legacy_imap);
        }
        return acct.getProtocol(ctx).equals(sLegacyImapProtocol);
    }

    /**
     * Start foreground synchronization of the specified folder. This is called by
     * synchronizeMailbox or checkMail.
     * TODO this should use ID's instead of fully-restored objects
     * @return The status code for whether this operation succeeded.
     * @throws MessagingException
     */
    public static synchronized int synchronizeMailboxSynchronous(Context context,
            final Account account, final Mailbox folder, final boolean loadMore,
            final boolean uiRefresh) throws MessagingException {
        TrafficStats.setThreadStatsTag(TrafficFlags.getSyncFlags(context, account));
        final NotificationController nc = NotificationControllerCreatorHolder.getInstance(context);
        Store remoteStore = null;
        ImapIdleFolderHolder imapHolder = ImapIdleFolderHolder.getInstance();
        try {
            mSyncLock = true;

            // Unregister the imap idle
            if (account.getSyncInterval() == Account.CHECK_INTERVAL_PUSH) {
                imapHolder.unregisterIdledMailbox(folder.mId, false);
            } else {
                imapHolder.unregisterAccountIdledMailboxes(context, account.mId, false);
            }

            remoteStore = Store.getInstance(account, context);
            processPendingActionsSynchronous(context, account, remoteStore, uiRefresh);
            synchronizeMailboxGeneric(context, account, remoteStore, folder, loadMore, uiRefresh);

            // Clear authentication notification for this account
            nc.cancelLoginFailedNotification(account.mId);
        } catch (MessagingException e) {
            if (Logging.LOGD) {
                LogUtils.d(Logging.LOG_TAG, "synchronizeMailboxSynchronous", e);
            }
            if (e instanceof AuthenticationFailedException) {
                // Generate authentication notification
                nc.showLoginFailedNotificationSynchronous(account.mId, true /* incoming */);
            }
            throw e;
        } finally {
            mSyncLock = false;

            if (remoteStore != null) {
                remoteStore.closeConnections();
            }

            // Register the imap idle again
            if (account.getSyncInterval() == Account.CHECK_INTERVAL_PUSH) {
                imapHolder.registerMailboxForIdle(context, account, folder);
            }
        }
        // TODO: Rather than use exceptions as logic above, return the status and handle it
        // correctly in caller.
        return EmailServiceStatus.SUCCESS;
    }

    /**
     * Lightweight record for the first pass of message sync, where I'm just seeing if
     * the local message requires sync.  Later (for messages that need syncing) we'll do a full
     * readout from the DB.
     */
    private static class LocalMessageInfo {
        private static final int COLUMN_ID = 0;
        private static final int COLUMN_FLAG_READ = 1;
        private static final int COLUMN_FLAG_FAVORITE = 2;
        private static final int COLUMN_FLAG_LOADED = 3;
        private static final int COLUMN_SERVER_ID = 4;
        private static final int COLUMN_FLAGS =  5;
        private static final int COLUMN_TIMESTAMP =  6;
        private static final String[] PROJECTION = {
                MessageColumns._ID,
                MessageColumns.FLAG_READ,
                MessageColumns.FLAG_FAVORITE,
                MessageColumns.FLAG_LOADED,
                SyncColumns.SERVER_ID,
                MessageColumns.FLAGS,
                MessageColumns.TIMESTAMP
        };

        final long mId;
        final boolean mFlagRead;
        final boolean mFlagFavorite;
        final int mFlagLoaded;
        final String mServerId;
        final int mFlags;
        final long mTimestamp;

        public LocalMessageInfo(Cursor c) {
            mId = c.getLong(COLUMN_ID);
            mFlagRead = c.getInt(COLUMN_FLAG_READ) != 0;
            mFlagFavorite = c.getInt(COLUMN_FLAG_FAVORITE) != 0;
            mFlagLoaded = c.getInt(COLUMN_FLAG_LOADED);
            mServerId = c.getString(COLUMN_SERVER_ID);
            mFlags = c.getInt(COLUMN_FLAGS);
            mTimestamp = c.getLong(COLUMN_TIMESTAMP);
            // Note: mailbox key and account key not needed - they are projected for the SELECT
        }
    }

    private static class OldestTimestampInfo {
        private static final int COLUMN_OLDEST_TIMESTAMP = 0;
        private static final String[] PROJECTION = new String[] {
            "MIN(" + MessageColumns.TIMESTAMP + ")"
        };
    }

    /**
     * Load the structure and body of messages not yet synced
     * @param account the account we're syncing
     * @param remoteFolder the (open) Folder we're working on
     * @param messages an array of Messages we've got headers for
     * @param toMailbox the destination mailbox we're syncing
     * @throws MessagingException
     */
    static void loadUnsyncedMessages(final Context context, final Account account,
            Folder remoteFolder, ArrayList<Message> messages, final Mailbox toMailbox)
            throws MessagingException {

        FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.STRUCTURE);
        remoteFolder.fetch(messages.toArray(new Message[messages.size()]), fp, null);
        Message [] oneMessageArray = new Message[1];
        for (Message message : messages) {
            // Build a list of parts we are interested in. Text parts will be downloaded
            // right now, attachments will be left for later.
            ArrayList<Part> viewables = new ArrayList<Part>();
            ArrayList<Part> attachments = new ArrayList<Part>();
            MimeUtility.collectParts(message, viewables, attachments);
            // Download the viewables immediately
            oneMessageArray[0] = message;
            for (Part part : viewables) {
                fp.clear();
                fp.add(part);
                remoteFolder.fetch(oneMessageArray, fp, null);
            }
            // Store the updated message locally and mark it fully loaded
            Utilities.copyOneMessageToProvider(context, message, account, toMailbox,
                    EmailContent.Message.FLAG_LOADED_COMPLETE);
        }
    }

    public static void downloadFlagAndEnvelope(final Context context, final Account account,
            final Mailbox mailbox, Folder remoteFolder, ArrayList<Message> unsyncedMessages,
            HashMap<String, LocalMessageInfo> localMessageMap, final ArrayList<Long> unseenMessages)
            throws MessagingException {
        FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.FLAGS);
        fp.add(FetchProfile.Item.ENVELOPE);

        final HashMap<String, LocalMessageInfo> localMapCopy;
        if (localMessageMap != null)
            localMapCopy = new HashMap<String, LocalMessageInfo>(localMessageMap);
        else {
            localMapCopy = new HashMap<String, LocalMessageInfo>();
        }

        remoteFolder.fetch(unsyncedMessages.toArray(new Message[unsyncedMessages.size()]), fp,
                new MessageRetrievalListener() {
                    @Override
                    public void messageRetrieved(Message message) {
                        try {
                            // Determine if the new message was already known (e.g. partial)
                            // And create or reload the full message info
                            final LocalMessageInfo localMessageInfo =
                                    localMapCopy.get(message.getUid());
                            final boolean localExists = localMessageInfo != null;

                            if (!localExists && message.isSet(Flag.DELETED)) {
                                // This is a deleted message that we don't have locally, so don't
                                // create it
                                return;
                            }

                            final EmailContent.Message localMessage;
                            if (!localExists) {
                                localMessage = new EmailContent.Message();
                            } else {
                                localMessage = EmailContent.Message.restoreMessageWithId(
                                        context, localMessageInfo.mId);
                            }

                            if (localMessage != null) {
                                try {
                                    // Copy the fields that are available into the message
                                    LegacyConversions.updateMessageFields(localMessage,
                                            message, account.mId, mailbox.mId);
                                    // Commit the message to the local store
                                    Utilities.saveOrUpdate(localMessage, context);
                                    // Track the "new" ness of the downloaded message
                                    if (!message.isSet(Flag.SEEN) && unseenMessages != null) {
                                        unseenMessages.add(localMessage.mId);
                                    }
                                } catch (MessagingException me) {
                                    LogUtils.e(Logging.LOG_TAG,
                                            "Error while copying downloaded message." + me);
                                }
                            }
                        }
                        catch (Exception e) {
                            LogUtils.e(Logging.LOG_TAG,
                                    "Error while storing downloaded message." + e.toString());
                        }
                    }

                    @Override
                    public void loadAttachmentProgress(int progress) {
                    }
                });

    }

    /**
     * Synchronizer for IMAP.
     *
     * TODO Break this method up into smaller chunks.
     *
     * @param account the account to sync
     * @param mailbox the mailbox to sync
     * @param loadMore whether we should be loading more older messages
     * @param uiRefresh whether this request is in response to a user action
     * @throws MessagingException
     */
    private synchronized static void synchronizeMailboxGeneric(final Context context,
            final Account account, Store remoteStore, final Mailbox mailbox, final boolean loadMore,
            final boolean uiRefresh)
            throws MessagingException {

        LogUtils.d(Logging.LOG_TAG, "synchronizeMailboxGeneric " + account + " " + mailbox + " "
                + loadMore + " " + uiRefresh);

        final ArrayList<Long> unseenMessages = new ArrayList<Long>();

        ContentResolver resolver = context.getContentResolver();

        // 0. We do not ever sync DRAFTS or OUTBOX (down or up)
        if (mailbox.mType == Mailbox.TYPE_DRAFTS || mailbox.mType == Mailbox.TYPE_OUTBOX) {
            return;
        }

        // 1. Figure out what our sync window should be.
        long endDate;

        // We will do a full sync if the user has actively requested a sync, or if it has been
        // too long since the last full sync.
        // If we have rebooted since the last full sync, then we may get a negative
        // timeSinceLastFullSync. In this case, we don't know how long it's been since the last
        // full sync so we should perform the full sync.
        final long timeSinceLastFullSync = SystemClock.elapsedRealtime() -
                mailbox.mLastFullSyncTime;
        final boolean fullSync = (uiRefresh || loadMore ||
                timeSinceLastFullSync >= FULL_SYNC_INTERVAL_MILLIS || timeSinceLastFullSync < 0);

        if (fullSync) {
            int syncLookBack = mailbox.mSyncLookback == SyncWindow.SYNC_WINDOW_ACCOUNT
                    ? account.mSyncLookback
                    : mailbox.mSyncLookback;
            endDate = System.currentTimeMillis() -
                    (SyncWindow.toDays(syncLookBack) * DateUtils.DAY_IN_MILLIS);
            LogUtils.d(Logging.LOG_TAG, "full sync: original window: now - " + endDate);
        } else {
            // We are doing a frequent, quick sync. This only syncs a small time window, so that
            // we wil get any new messages, but not spend a lot of bandwidth downloading
            // messageIds that we most likely already have.
            endDate = System.currentTimeMillis() - QUICK_SYNC_WINDOW_MILLIS;
            LogUtils.d(Logging.LOG_TAG, "quick sync: original window: now - " + endDate);
        }

        // 2. Open the remote folder and create the remote folder if necessary
        // The account might have been deleted
        if (remoteStore == null) {
            LogUtils.d(Logging.LOG_TAG, "account is apparently deleted");
            return;
        }
        final Folder remoteFolder = remoteStore.getFolder(mailbox.mServerId);

        // If the folder is a "special" folder we need to see if it exists
        // on the remote server. It if does not exist we'll try to create it. If we
        // can't create we'll abort. This will happen on every single Pop3 folder as
        // designed and on Imap folders during error conditions. This allows us
        // to treat Pop3 and Imap the same in this code.
        if (mailbox.mType == Mailbox.TYPE_TRASH || mailbox.mType == Mailbox.TYPE_SENT) {
            if (!remoteFolder.exists()) {
                if (!remoteFolder.create(FolderType.HOLDS_MESSAGES)) {
                    LogUtils.w(Logging.LOG_TAG, "could not create remote folder type %d",
                        mailbox.mType);
                    return;
                }
            }
        }
        remoteFolder.open(OpenMode.READ_WRITE);

        // 3. Trash any remote messages that are marked as trashed locally.
        // TODO - this comment was here, but no code was here.

        // 4. Get the number of messages on the server.
        // TODO: this value includes deleted but unpurged messages, and so slightly mismatches
        // the contents of our DB since we drop deleted messages. Figure out what to do about this.
        final int remoteMessageCount = remoteFolder.getMessageCount();

        // 5. Save folder message count locally.
        mailbox.updateMessageCount(context, remoteMessageCount);

        // 6. Get all message Ids in our sync window:
        Message[] remoteMessages;
        remoteMessages = remoteFolder.getMessages(0, endDate, null);
        LogUtils.d(Logging.LOG_TAG, "received " + remoteMessages.length + " messages");

        // 7. See if we need any additional messages beyond our date query range results.
        // If we do, keep increasing the size of our query window until we have
        // enough, or until we have all messages in the mailbox.
        int totalCountNeeded;
        if (loadMore) {
            totalCountNeeded = remoteMessages.length + LOAD_MORE_MIN_INCREMENT;
        } else {
            totalCountNeeded = remoteMessages.length;
            if (fullSync && totalCountNeeded < MINIMUM_MESSAGES_TO_SYNC) {
                totalCountNeeded = MINIMUM_MESSAGES_TO_SYNC;
            }
        }
        LogUtils.d(Logging.LOG_TAG, "need " + totalCountNeeded + " total");

        final int additionalMessagesNeeded = totalCountNeeded - remoteMessages.length;
        if (additionalMessagesNeeded > 0) {
            LogUtils.d(Logging.LOG_TAG, "trying to get " + additionalMessagesNeeded + " more");
            long startDate = endDate - 1;
            Message[] additionalMessages = new Message[0];
            long windowIncreaseSize = INITIAL_WINDOW_SIZE_INCREASE;
            while (additionalMessages.length < additionalMessagesNeeded && endDate > 0) {
                endDate = endDate - windowIncreaseSize;
                if (endDate < 0) {
                    LogUtils.d(Logging.LOG_TAG, "window size too large, this is the last attempt");
                    endDate = 0;
                }
                LogUtils.d(Logging.LOG_TAG,
                        "requesting additional messages from range " + startDate + " - " + endDate);
                additionalMessages = remoteFolder.getMessages(startDate, endDate, null);

                // If don't get enough messages with the first window size expansion,
                // we need to accelerate rate at which the window expands. Otherwise,
                // if there were no messages for several weeks, we'd always end up
                // performing dozens of queries.
                windowIncreaseSize *= 2;
            }

            LogUtils.d(Logging.LOG_TAG, "additionalMessages " + additionalMessages.length);
            if (additionalMessages.length < additionalMessagesNeeded) {
                // We have attempted to load a window that goes all the way back to time zero,
                // but we still don't have as many messages as the server says are in the inbox.
                // This is not expected to happen.
                LogUtils.e(Logging.LOG_TAG, "expected to find " + additionalMessagesNeeded
                        + " more messages, only got " + additionalMessages.length);
            }
            int additionalToKeep = additionalMessages.length;
            if (additionalMessages.length > LOAD_MORE_MAX_INCREMENT) {
                // We have way more additional messages than intended, drop some of them.
                // The last messages are the most recent, so those are the ones we need to keep.
                additionalToKeep = LOAD_MORE_MAX_INCREMENT;
            }

            // Copy the messages into one array.
            Message[] allMessages = new Message[remoteMessages.length + additionalToKeep];
            System.arraycopy(remoteMessages, 0, allMessages, 0, remoteMessages.length);
            // additionalMessages may have more than we need, only copy the last
            // several. These are the most recent messages in that set because
            // of the way IMAP server returns messages.
            System.arraycopy(additionalMessages, additionalMessages.length - additionalToKeep,
                    allMessages, remoteMessages.length, additionalToKeep);
            remoteMessages = allMessages;
        }

        // 8. Get the all of the local messages within the sync window, and create
        // an index of the uids.
        // The IMAP query for messages ignores time, and only looks at the date part of the endDate.
        // So if we query for messages since Aug 11 at 3:00 PM, we can get messages from any time
        // on Aug 11. Our IMAP query results can include messages up to 24 hours older than endDate,
        // or up to 25 hours older at a daylight savings transition.
        // It is important that we have the Id of any local message that could potentially be
        // returned by the IMAP query, or we will create duplicate copies of the same messages.
        // So we will increase our local query range by this much.
        // Note that this complicates deletion: It's not okay to delete anything that is in the
        // localMessageMap but not in the remote result, because we know that we may be getting
        // Ids of local messages that are outside the IMAP query window.
        Cursor localUidCursor = null;
        HashMap<String, LocalMessageInfo> localMessageMap = new HashMap<String, LocalMessageInfo>();
        try {
            // FLAG: There is a problem that causes us to store the wrong date on some messages,
            // so messages get a date of zero. If we filter these messages out and don't put them
            // in our localMessageMap, then we'll end up loading the same message again.
            // See b/10508861
//            final long queryEndDate = endDate - DateUtils.DAY_IN_MILLIS - DateUtils.HOUR_IN_MILLIS;
            final long queryEndDate = 0;
            localUidCursor = resolver.query(
                    EmailContent.Message.CONTENT_URI,
                    LocalMessageInfo.PROJECTION,
                    EmailContent.MessageColumns.ACCOUNT_KEY + "=?"
                            + " AND " + MessageColumns.MAILBOX_KEY + "=?"
                            + " AND " + MessageColumns.TIMESTAMP + ">=?",
                    new String[] {
                            String.valueOf(account.mId),
                            String.valueOf(mailbox.mId),
                            String.valueOf(queryEndDate) },
                    null);
            while (localUidCursor.moveToNext()) {
                LocalMessageInfo info = new LocalMessageInfo(localUidCursor);
                // If the message has no server id, it's local only. This should only happen for
                // mail created on the client that has failed to upsync. We want to ignore such
                // mail during synchronization (i.e. leave it as-is and let the next sync try again
                // to upsync).
                if (!TextUtils.isEmpty(info.mServerId)) {
                    localMessageMap.put(info.mServerId, info);
                }
            }
        } finally {
            if (localUidCursor != null) {
                localUidCursor.close();
            }
        }

        // 9. Get a list of the messages that are in the remote list but not on the
        // local store, or messages that are in the local store but failed to download
        // on the last sync. These are the new messages that we will download.
        // Note, we also skip syncing messages which are flagged as "deleted message" sentinels,
        // because they are locally deleted and we don't need or want the old message from
        // the server.
        final ArrayList<Message> unsyncedMessages = new ArrayList<Message>();
        final HashMap<String, Message> remoteUidMap = new HashMap<String, Message>();
        // Process the messages in the reverse order we received them in. This means that
        // we load the most recent one first, which gives a better user experience.
        for (int i = remoteMessages.length - 1; i >= 0; i--) {
            Message message = remoteMessages[i];
            LogUtils.d(Logging.LOG_TAG, "remote message " + message.getUid());
            remoteUidMap.put(message.getUid(), message);

            LocalMessageInfo localMessage = localMessageMap.get(message.getUid());

            // localMessage == null -> message has never been created (not even headers)
            // mFlagLoaded = UNLOADED -> message created, but none of body loaded
            // mFlagLoaded = PARTIAL -> message created, a "sane" amt of body has been loaded
            // mFlagLoaded = COMPLETE -> message body has been completely loaded
            // mFlagLoaded = DELETED -> message has been deleted
            // Only the first two of these are "unsynced", so let's retrieve them
            if (localMessage == null ||
                    (localMessage.mFlagLoaded == EmailContent.Message.FLAG_LOADED_UNLOADED) ||
                    (localMessage.mFlagLoaded == EmailContent.Message.FLAG_LOADED_PARTIAL)) {
                unsyncedMessages.add(message);
            }
        }

        // 10. Download basic info about the new/unloaded messages (if any)
        /*
         * Fetch the flags and envelope only of the new messages. This is intended to get us
         * critical data as fast as possible, and then we'll fill in the details.
         */
        if (unsyncedMessages.size() > 0) {
            downloadFlagAndEnvelope(context, account, mailbox, remoteFolder, unsyncedMessages,
                    localMessageMap, unseenMessages);
        }

        // 11. Refresh the flags for any messages in the local store that we didn't just download.
        // TODO This is a bit wasteful because we're also updating any messages we already did get
        // the flags and envelope for previously.
        // TODO: the fetch() function, and others, should take List<>s of messages, not
        // arrays of messages.
        FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.FLAGS);
        if (remoteMessages.length > MAX_MESSAGES_TO_FETCH) {
            List<Message> remoteMessageList = Arrays.asList(remoteMessages);
            for (int start = 0; start < remoteMessageList.size(); start += MAX_MESSAGES_TO_FETCH) {
                int end = start + MAX_MESSAGES_TO_FETCH;
                if (end >= remoteMessageList.size()) {
                    end = remoteMessageList.size() - 1;
                }
                List<Message> chunk = remoteMessageList.subList(start, end);
                final Message[] partialArray = chunk.toArray(new Message[chunk.size()]);
                // Fetch this one chunk of messages
                remoteFolder.fetch(partialArray, fp, null);
            }
        } else {
            remoteFolder.fetch(remoteMessages, fp, null);
        }
        boolean remoteSupportsSeen = false;
        boolean remoteSupportsFlagged = false;
        boolean remoteSupportsAnswered = false;
        for (Flag flag : remoteFolder.getPermanentFlags()) {
            if (flag == Flag.SEEN) {
                remoteSupportsSeen = true;
            }
            if (flag == Flag.FLAGGED) {
                remoteSupportsFlagged = true;
            }
            if (flag == Flag.ANSWERED) {
                remoteSupportsAnswered = true;
            }
        }

        // 12. Update SEEN/FLAGGED/ANSWERED (star) flags (if supported remotely - e.g. not for POP3)
        if (remoteSupportsSeen || remoteSupportsFlagged || remoteSupportsAnswered) {
            for (Message remoteMessage : remoteMessages) {
                LocalMessageInfo localMessageInfo = localMessageMap.get(remoteMessage.getUid());
                if (localMessageInfo == null) {
                    continue;
                }
                boolean localSeen = localMessageInfo.mFlagRead;
                boolean remoteSeen = remoteMessage.isSet(Flag.SEEN);
                boolean newSeen = (remoteSupportsSeen && (remoteSeen != localSeen));
                boolean localFlagged = localMessageInfo.mFlagFavorite;
                boolean remoteFlagged = remoteMessage.isSet(Flag.FLAGGED);
                boolean newFlagged = (remoteSupportsFlagged && (localFlagged != remoteFlagged));
                int localFlags = localMessageInfo.mFlags;
                boolean localAnswered = (localFlags & EmailContent.Message.FLAG_REPLIED_TO) != 0;
                boolean remoteAnswered = remoteMessage.isSet(Flag.ANSWERED);
                boolean newAnswered = (remoteSupportsAnswered && (localAnswered != remoteAnswered));
                if (newSeen || newFlagged || newAnswered) {
                    Uri uri = ContentUris.withAppendedId(
                            EmailContent.Message.CONTENT_URI, localMessageInfo.mId);
                    ContentValues updateValues = new ContentValues();
                    updateValues.put(MessageColumns.FLAG_READ, remoteSeen);
                    updateValues.put(MessageColumns.FLAG_FAVORITE, remoteFlagged);
                    if (remoteAnswered) {
                        localFlags |= EmailContent.Message.FLAG_REPLIED_TO;
                    } else {
                        localFlags &= ~EmailContent.Message.FLAG_REPLIED_TO;
                    }
                    updateValues.put(MessageColumns.FLAGS, localFlags);
                    resolver.update(uri, updateValues, null, null);
                }
            }
        }

        // 12.5 Remove messages that are marked as deleted so that we drop them from the DB in the
        // next step
        for (final Message remoteMessage : remoteMessages) {
            if (remoteMessage.isSet(Flag.DELETED)) {
                remoteUidMap.remove(remoteMessage.getUid());
                unsyncedMessages.remove(remoteMessage);
            }
        }

        // 13. Remove messages that are in the local store and in the current sync window,
        // but no longer on the remote store. Note that localMessageMap can contain messages
        // that are not actually in our sync window. We need to check the timestamp to ensure
        // that it is before deleting.
        for (final LocalMessageInfo info : localMessageMap.values()) {
            // If this message is inside our sync window, and we cannot find it in our list
            // of remote messages, then we know it's been deleted from the server.
            if (info.mTimestamp >= endDate && !remoteUidMap.containsKey(info.mServerId)) {
                // Delete associated data (attachment files)
                // Attachment & Body records are auto-deleted when we delete the Message record
                AttachmentUtilities.deleteAllAttachmentFiles(context, account.mId, info.mId);

                // Delete the message itself
                final Uri uriToDelete = ContentUris.withAppendedId(
                        EmailContent.Message.CONTENT_URI, info.mId);
                resolver.delete(uriToDelete, null, null);

                // Delete extra rows (e.g. updated or deleted)
                final Uri updateRowToDelete = ContentUris.withAppendedId(
                        EmailContent.Message.UPDATED_CONTENT_URI, info.mId);
                resolver.delete(updateRowToDelete, null, null);
                final Uri deleteRowToDelete = ContentUris.withAppendedId(
                        EmailContent.Message.DELETED_CONTENT_URI, info.mId);
                resolver.delete(deleteRowToDelete, null, null);
            }
        }

        loadUnsyncedMessages(context, account, remoteFolder, unsyncedMessages, mailbox);

        if (fullSync) {
            mailbox.updateLastFullSyncTime(context, SystemClock.elapsedRealtime());
        }

        // 14. Clean up and report results
        remoteFolder.close(false);
    }

    private synchronized static void processImapFetchChanges(Context ctx, Account acct,
            Mailbox mailbox, List<String> uids) throws MessagingException {

        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "Imap IDLE Sync WakeLock");

        NotificationController nc = null;
        Store remoteStore = null;
        final ImapIdleFolderHolder imapHolder = ImapIdleFolderHolder.getInstance();

        try {
            mSyncLock = true;
            wl.acquire();

            TrafficStats.setThreadStatsTag(TrafficFlags.getSyncFlags(ctx, acct));
            nc = NotificationControllerCreatorHolder.getInstance(ctx);

            remoteStore = Store.getInstance(acct, ctx);

            final ContentResolver resolver = ctx.getContentResolver();

            // Don't sync if account is not set to idled
            if (acct.getSyncInterval() != Account.CHECK_INTERVAL_PUSH) {
                return;
            }

            // 1. Open the remote store & folder
            ImapFolder remoteFolder;
            synchronized (imapHolder.mIdledFolders) {
                remoteFolder = imapHolder.mIdledFolders.get((int) mailbox.mId);
            }
            if (remoteFolder == null || remoteFolder.isIdling()) {
                remoteFolder = (ImapFolder) remoteStore.getFolder(mailbox.mServerId);
            }
            if (!remoteFolder.exists()) {
                return;
            }
            remoteFolder.open(OpenMode.READ_WRITE);
            if (remoteFolder.getMode() != OpenMode.READ_WRITE) {
                return;
            }

            // 1.- Retrieve the messages
            Message[] remoteMessages = remoteFolder.getMessages(
                    uids.toArray(new String[uids.size()]), null);

            // 2.- Refresh flags
            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.FLAGS);
            remoteFolder.fetch(remoteMessages, fp, null);

            boolean remoteSupportsSeen = false;
            boolean remoteSupportsFlagged = false;
            boolean remoteSupportsAnswered = false;
            for (Flag flag : remoteFolder.getPermanentFlags()) {
                if (flag == Flag.SEEN) {
                    remoteSupportsSeen = true;
                }
                if (flag == Flag.FLAGGED) {
                    remoteSupportsFlagged = true;
                }
                if (flag == Flag.ANSWERED) {
                    remoteSupportsAnswered = true;
                }
            }

            // 3.- Retrieve a reference of the local messages
            HashMap<String, LocalMessageInfo> localMessageMap = new HashMap<>();
            for (Message remoteMessage : remoteMessages) {
                Cursor localUidCursor = null;
                try {
                    localUidCursor = resolver.query(
                            EmailContent.Message.CONTENT_URI,
                            LocalMessageInfo.PROJECTION,
                            EmailContent.MessageColumns.ACCOUNT_KEY + "=?"
                                    + " AND " + MessageColumns.MAILBOX_KEY + "=?"
                                    + " AND " + MessageColumns.SERVER_ID + "=?",
                            new String[] {
                                    String.valueOf(acct.mId),
                                    String.valueOf(mailbox.mId),
                                    String.valueOf(remoteMessage.getUid()) },
                            null);
                    if (localUidCursor != null && localUidCursor.moveToNext()) {
                        LocalMessageInfo info = new LocalMessageInfo(localUidCursor);
                        localMessageMap.put(info.mServerId, info);
                    }
                } finally {
                    if (localUidCursor != null) {
                        localUidCursor.close();
                    }
                }
            }

            // 5.- Add to the list of new messages
            final ArrayList<Long> unseenMessages = new ArrayList<Long>();
            final ArrayList<Message> unsyncedMessages = new ArrayList<Message>();
            for (Message remoteMessage : remoteMessages) {
                LocalMessageInfo localMessage = localMessageMap.get(remoteMessage.getUid());

                // localMessage == null -> message has never been created (not even headers)
                // mFlagLoaded = UNLOADED -> message created, but none of body loaded
                // mFlagLoaded = PARTIAL -> message created, a "sane" amt of body has been loaded
                // mFlagLoaded = COMPLETE -> message body has been completely loaded
                // mFlagLoaded = DELETED -> message has been deleted
                // Only the first two of these are "unsynced", so let's retrieve them
                if (localMessage == null ||
                        (localMessage.mFlagLoaded == EmailContent.Message.FLAG_LOADED_UNLOADED) ||
                        (localMessage.mFlagLoaded == EmailContent.Message.FLAG_LOADED_PARTIAL)) {
                    unsyncedMessages.add(remoteMessage);
                }
            }

            // 6. Download basic info about the new/unloaded messages (if any)
            /*
             * Fetch the flags and envelope only of the new messages. This is intended to get us
             * critical data as fast as possible, and then we'll fill in the details.
             */
            if (unsyncedMessages.size() > 0) {
                downloadFlagAndEnvelope(ctx, acct, mailbox, remoteFolder, unsyncedMessages,
                        localMessageMap, unseenMessages);
            }

            // 7. Update SEEN/FLAGGED/ANSWERED (star) flags
            if (remoteSupportsSeen || remoteSupportsFlagged || remoteSupportsAnswered) {
                for (Message remoteMessage : remoteMessages) {
                    LocalMessageInfo localMessageInfo = localMessageMap.get(remoteMessage.getUid());
                    if (localMessageInfo == null) {
                        continue;
                    }
                    boolean localSeen = localMessageInfo.mFlagRead;
                    boolean remoteSeen = remoteMessage.isSet(Flag.SEEN);
                    boolean newSeen = (remoteSupportsSeen && (remoteSeen != localSeen));
                    boolean localFlagged = localMessageInfo.mFlagFavorite;
                    boolean remoteFlagged = remoteMessage.isSet(Flag.FLAGGED);
                    boolean newFlagged = (remoteSupportsFlagged && (localFlagged != remoteFlagged));
                    int localFlags = localMessageInfo.mFlags;
                    boolean localAnswered = (localFlags &
                            EmailContent.Message.FLAG_REPLIED_TO) != 0;
                    boolean remoteAnswered = remoteMessage.isSet(Flag.ANSWERED);
                    boolean newAnswered = (remoteSupportsAnswered &&
                            (localAnswered != remoteAnswered));
                    if (newSeen || newFlagged || newAnswered) {
                        Uri uri = ContentUris.withAppendedId(
                                EmailContent.Message.CONTENT_URI, localMessageInfo.mId);
                        ContentValues updateValues = new ContentValues();
                        updateValues.put(MessageColumns.FLAG_READ, remoteSeen);
                        updateValues.put(MessageColumns.FLAG_FAVORITE, remoteFlagged);
                        if (remoteAnswered) {
                            localFlags |= EmailContent.Message.FLAG_REPLIED_TO;
                        } else {
                            localFlags &= ~EmailContent.Message.FLAG_REPLIED_TO;
                        }
                        updateValues.put(MessageColumns.FLAGS, localFlags);
                        resolver.update(uri, updateValues, null, null);
                    }
                }
            }

            // 8.- Remove remote deleted messages
            for (final Message remoteMessage : remoteMessages) {
                if (!remoteMessage.isSet(Flag.DELETED)) {
                    continue;
                }

                // Make sure we don't sync messages (again) that we deleted locally
                // before and now get a flag update (DELETED being set) for now.
                unsyncedMessages.remove(remoteMessage);

                LocalMessageInfo info = localMessageMap.get(remoteMessage.getUid());
                if (info == null) {
                    continue;
                }

                // Delete associated data (attachment files)
                // Attachment & Body records are auto-deleted when we delete the Message record
                AttachmentUtilities.deleteAllAttachmentFiles(ctx, acct.mId, info.mId);

                // Delete the message itself
                final Uri uriToDelete = ContentUris.withAppendedId(
                        EmailContent.Message.CONTENT_URI, info.mId);
                resolver.delete(uriToDelete, null, null);

                // Delete extra rows (e.g. updated or deleted)
                final Uri updateRowToDelete = ContentUris.withAppendedId(
                        EmailContent.Message.UPDATED_CONTENT_URI, info.mId);
                resolver.delete(updateRowToDelete, null, null);
                final Uri deleteRowToDelete = ContentUris.withAppendedId(
                        EmailContent.Message.DELETED_CONTENT_URI, info.mId);
                resolver.delete(deleteRowToDelete, null, null);
            }

            // 9.- Load unsynced messages
            loadUnsyncedMessages(ctx, acct, remoteFolder, unsyncedMessages, mailbox);

            // 10. Remove messages that are in the local store but no in the current sync window
            int syncLookBack = mailbox.mSyncLookback == SyncWindow.SYNC_WINDOW_ACCOUNT
                    ? acct.mSyncLookback
                    : mailbox.mSyncLookback;
            long endDate = System.currentTimeMillis() -
                    (SyncWindow.toDays(syncLookBack) * DateUtils.DAY_IN_MILLIS);
            LogUtils.d(Logging.LOG_TAG, "full sync: original window: now - " + endDate);
            for (final LocalMessageInfo info : localMessageMap.values()) {
                // If this message is inside our sync window, and we cannot find it in our list
                // of remote messages, then we know it's been deleted from the server.
                if (info.mTimestamp < endDate) {
                    // Delete associated data (attachment files)
                    // Attachment & Body records are auto-deleted when we delete the Message record
                    AttachmentUtilities.deleteAllAttachmentFiles(ctx, acct.mId, info.mId);

                    // Delete the message itself
                    final Uri uriToDelete = ContentUris.withAppendedId(
                            EmailContent.Message.CONTENT_URI, info.mId);
                    resolver.delete(uriToDelete, null, null);

                    // Delete extra rows (e.g. updated or deleted)
                    final Uri updateRowToDelete = ContentUris.withAppendedId(
                            EmailContent.Message.UPDATED_CONTENT_URI, info.mId);
                    resolver.delete(updateRowToDelete, null, null);
                    final Uri deleteRowToDelete = ContentUris.withAppendedId(
                            EmailContent.Message.DELETED_CONTENT_URI, info.mId);
                    resolver.delete(deleteRowToDelete, null, null);
                }
            }

            // Clear authentication notification for this account
            nc.cancelLoginFailedNotification(acct.mId);

        } catch (MessagingException ex) {
            if (Logging.LOGD) {
                LogUtils.d(Logging.LOG_TAG, ex, "processImapFetchChanges");
            }
            if (ex instanceof AuthenticationFailedException) {
                // Generate authentication notification
                if (nc != null) {
                    nc.showLoginFailedNotificationSynchronous(acct.mId, true /* incoming */);
                }
            }
            throw ex;
        } finally {
            mSyncLock = false;
            wl.release();

            if (remoteStore != null) {
                remoteStore.closeConnections();

                final boolean registered;
                synchronized (imapHolder.mIdledFolders) {
                    registered = imapHolder.mIdledFolders.indexOfKey((int) mailbox.mId) >= 0;
                }
                // Register the imap idle again
                if (registered && acct.getSyncInterval() == Account.CHECK_INTERVAL_PUSH) {
                    imapHolder.registerMailboxForIdle(ctx, acct, mailbox);
                }
            }
        }
    }

    /**
     * Find messages in the updated table that need to be written back to server.
     *
     * Handles:
     *   Read/Unread
     *   Flagged
     *   Append (upload)
     *   Move To Trash
     *   Empty trash
     * TODO:
     *   Move
     *
     * @param account the account to scan for pending actions
     * @throws MessagingException
     */
    private static void processPendingActionsSynchronous(Context context, Account account,
            Store remoteStore, boolean manualSync)
            throws MessagingException {
        TrafficStats.setThreadStatsTag(TrafficFlags.getSyncFlags(context, account));
        String[] accountIdArgs = new String[] { Long.toString(account.mId) };

        // Handle deletes first, it's always better to get rid of things first
        processPendingDeletesSynchronous(context, account, remoteStore, accountIdArgs);

        // Handle uploads (currently, only to sent messages)
        processPendingUploadsSynchronous(context, account, remoteStore, accountIdArgs, manualSync);

        // Now handle updates / upsyncs
        processPendingUpdatesSynchronous(context, account, remoteStore, accountIdArgs);
    }

    /**
     * Get the mailbox corresponding to the remote location of a message; this will normally be
     * the mailbox whose _id is mailboxKey, except for search results, where we must look it up
     * by serverId.
     *
     * @param message the message in question
     * @return the mailbox in which the message resides on the server
     */
    private static Mailbox getRemoteMailboxForMessage(
            Context context, EmailContent.Message message) {
        // If this is a search result, use the protocolSearchInfo field to get the server info
        if (!TextUtils.isEmpty(message.mProtocolSearchInfo)) {
            long accountKey = message.mAccountKey;
            String protocolSearchInfo = message.mProtocolSearchInfo;
            if (accountKey == mLastSearchAccountKey &&
                    protocolSearchInfo.equals(mLastSearchServerId)) {
                return mLastSearchRemoteMailbox;
            }
            Cursor c = context.getContentResolver().query(Mailbox.CONTENT_URI,
                    Mailbox.CONTENT_PROJECTION, Mailbox.PATH_AND_ACCOUNT_SELECTION,
                    new String[] {protocolSearchInfo, Long.toString(accountKey) },
                    null);
            try {
                if (c.moveToNext()) {
                    Mailbox mailbox = new Mailbox();
                    mailbox.restore(c);
                    mLastSearchAccountKey = accountKey;
                    mLastSearchServerId = protocolSearchInfo;
                    mLastSearchRemoteMailbox = mailbox;
                    return mailbox;
                } else {
                    return null;
                }
            } finally {
                c.close();
            }
        } else {
            return Mailbox.restoreMailboxWithId(context, message.mMailboxKey);
        }
    }

    /**
     * Scan for messages that are in the Message_Deletes table, look for differences that
     * we can deal with, and do the work.
     */
    private static void processPendingDeletesSynchronous(Context context, Account account,
            Store remoteStore, String[] accountIdArgs) {
        Cursor deletes = context.getContentResolver().query(
                EmailContent.Message.DELETED_CONTENT_URI,
                EmailContent.Message.CONTENT_PROJECTION,
                EmailContent.MessageColumns.ACCOUNT_KEY + "=?", accountIdArgs,
                EmailContent.MessageColumns.MAILBOX_KEY);
        long lastMessageId = -1;
        try {
            // loop through messages marked as deleted
            while (deletes.moveToNext()) {
                EmailContent.Message oldMessage =
                        EmailContent.getContent(context, deletes, EmailContent.Message.class);

                if (oldMessage != null) {
                    lastMessageId = oldMessage.mId;

                    Mailbox mailbox = getRemoteMailboxForMessage(context, oldMessage);
                    if (mailbox == null) {
                        continue; // Mailbox removed. Move to the next message.
                    }
                    final boolean deleteFromTrash = mailbox.mType == Mailbox.TYPE_TRASH;

                    // Dispatch here for specific change types
                    if (deleteFromTrash) {
                        // Move message to trash
                        processPendingDeleteFromTrash(remoteStore, mailbox, oldMessage);
                    }

                    // Finally, delete the update
                    Uri uri = ContentUris.withAppendedId(EmailContent.Message.DELETED_CONTENT_URI,
                            oldMessage.mId);
                    context.getContentResolver().delete(uri, null, null);
                }
            }
        } catch (MessagingException me) {
            // Presumably an error here is an account connection failure, so there is
            // no point in continuing through the rest of the pending updates.
            if (DebugUtils.DEBUG) {
                LogUtils.d(Logging.LOG_TAG, "Unable to process pending delete for id="
                        + lastMessageId + ": " + me);
            }
        } finally {
            deletes.close();
        }
    }

    /**
     * Scan for messages that are in Sent, and are in need of upload,
     * and send them to the server. "In need of upload" is defined as:
     *  serverId == null (no UID has been assigned)
     * or
     *  message is in the updated list
     *
     * Note we also look for messages that are moving from drafts->outbox->sent. They never
     * go through "drafts" or "outbox" on the server, so we hang onto these until they can be
     * uploaded directly to the Sent folder.
     */
    private static void processPendingUploadsSynchronous(Context context, Account account,
            Store remoteStore, String[] accountIdArgs, boolean manualSync) {
        ContentResolver resolver = context.getContentResolver();
        // Find the Sent folder (since that's all we're uploading for now
        // TODO: Upsync for all folders? (In case a user moves mail from Sent before it is
        // handled. Also, this would generically solve allowing drafts to upload.)
        Cursor mailboxes = resolver.query(Mailbox.CONTENT_URI, Mailbox.ID_PROJECTION,
                MailboxColumns.ACCOUNT_KEY + "=?"
                + " and " + MailboxColumns.TYPE + "=" + Mailbox.TYPE_SENT,
                accountIdArgs, null);
        long lastMessageId = -1;
        try {
            while (mailboxes.moveToNext()) {
                long mailboxId = mailboxes.getLong(Mailbox.ID_PROJECTION_COLUMN);
                String[] mailboxKeyArgs = new String[] { Long.toString(mailboxId) };
                // Demand load mailbox
                Mailbox mailbox = null;

                // First handle the "new" messages (serverId == null)
                Cursor upsyncs1 = resolver.query(EmailContent.Message.CONTENT_URI,
                        EmailContent.Message.ID_PROJECTION,
                        MessageColumns.MAILBOX_KEY + "=?"
                        + " and (" + MessageColumns.SERVER_ID + " is null"
                        + " or " + MessageColumns.SERVER_ID + "=''" + ")",
                        mailboxKeyArgs,
                        null);
                try {
                    while (upsyncs1.moveToNext()) {
                        // Load the remote store if it will be needed
                        if (remoteStore == null) {
                            remoteStore = Store.getInstance(account, context);
                        }
                        // Load the mailbox if it will be needed
                        if (mailbox == null) {
                            mailbox = Mailbox.restoreMailboxWithId(context, mailboxId);
                            if (mailbox == null) {
                                continue; // Mailbox removed. Move to the next message.
                            }
                        }
                        // upsync the message
                        long id = upsyncs1.getLong(EmailContent.Message.ID_PROJECTION_COLUMN);
                        lastMessageId = id;
                        processUploadMessage(context, remoteStore, mailbox, id, manualSync);
                    }
                } finally {
                    if (upsyncs1 != null) {
                        upsyncs1.close();
                    }
                    if (remoteStore != null) {
                        remoteStore.closeConnections();
                    }
                }
            }
        } catch (MessagingException me) {
            // Presumably an error here is an account connection failure, so there is
            // no point in continuing through the rest of the pending updates.
            if (DebugUtils.DEBUG) {
                LogUtils.d(Logging.LOG_TAG, "Unable to process pending upsync for id="
                        + lastMessageId + ": " + me);
            }
        } finally {
            if (mailboxes != null) {
                mailboxes.close();
            }
        }
    }

    /**
     * Scan for messages that are in the Message_Updates table, look for differences that
     * we can deal with, and do the work.
     */
    private static void processPendingUpdatesSynchronous(Context context, Account account,
            Store remoteStore, String[] accountIdArgs) {
        ContentResolver resolver = context.getContentResolver();
        Cursor updates = resolver.query(EmailContent.Message.UPDATED_CONTENT_URI,
                EmailContent.Message.CONTENT_PROJECTION,
                EmailContent.MessageColumns.ACCOUNT_KEY + "=?", accountIdArgs,
                EmailContent.MessageColumns.MAILBOX_KEY);
        long lastMessageId = -1;
        try {
            // Demand load mailbox (note order-by to reduce thrashing here)
            Mailbox mailbox = null;
            // loop through messages marked as needing updates
            while (updates.moveToNext()) {
                boolean changeMoveToTrash = false;
                boolean changeRead = false;
                boolean changeFlagged = false;
                boolean changeMailbox = false;
                boolean changeAnswered = false;

                EmailContent.Message oldMessage =
                        EmailContent.getContent(context, updates, EmailContent.Message.class);
                lastMessageId = oldMessage.mId;
                EmailContent.Message newMessage =
                        EmailContent.Message.restoreMessageWithId(context, oldMessage.mId);
                if (newMessage != null) {
                    mailbox = Mailbox.restoreMailboxWithId(context, newMessage.mMailboxKey);
                    if (mailbox == null) {
                        continue; // Mailbox removed. Move to the next message.
                    }
                    if (oldMessage.mMailboxKey != newMessage.mMailboxKey) {
                        if (mailbox.mType == Mailbox.TYPE_TRASH) {
                            changeMoveToTrash = true;
                        } else {
                            changeMailbox = true;
                        }
                    }
                    changeRead = oldMessage.mFlagRead != newMessage.mFlagRead;
                    changeFlagged = oldMessage.mFlagFavorite != newMessage.mFlagFavorite;
                    changeAnswered = (oldMessage.mFlags & EmailContent.Message.FLAG_REPLIED_TO) !=
                            (newMessage.mFlags & EmailContent.Message.FLAG_REPLIED_TO);
                }

                // Load the remote store if it will be needed
                if (remoteStore == null &&
                        (changeMoveToTrash || changeRead || changeFlagged || changeMailbox ||
                                changeAnswered)) {
                    remoteStore = Store.getInstance(account, context);
                }

                // Dispatch here for specific change types
                if (changeMoveToTrash) {
                    // Move message to trash
                    processPendingMoveToTrash(context, remoteStore, mailbox, oldMessage,
                            newMessage);
                } else if (changeRead || changeFlagged || changeMailbox || changeAnswered) {
                    processPendingDataChange(context, remoteStore, mailbox, changeRead,
                            changeFlagged, changeMailbox, changeAnswered, oldMessage, newMessage);
                }

                // Finally, delete the update
                Uri uri = ContentUris.withAppendedId(EmailContent.Message.UPDATED_CONTENT_URI,
                        oldMessage.mId);
                resolver.delete(uri, null, null);
            }

        } catch (MessagingException me) {
            // Presumably an error here is an account connection failure, so there is
            // no point in continuing through the rest of the pending updates.
            if (DebugUtils.DEBUG) {
                LogUtils.d(Logging.LOG_TAG, "Unable to process pending update for id="
                        + lastMessageId + ": " + me);
            }
        } finally {
            updates.close();
        }
    }

    /**
     * Upsync an entire message. This must also unwind whatever triggered it (either by
     * updating the serverId, or by deleting the update record, or it's going to keep happening
     * over and over again.
     *
     * Note: If the message is being uploaded into an unexpected mailbox, we *do not* upload.
     * This is to avoid unnecessary uploads into the trash. Although the caller attempts to select
     * only the Drafts and Sent folders, this can happen when the update record and the current
     * record mismatch. In this case, we let the update record remain, because the filters
     * in processPendingUpdatesSynchronous() will pick it up as a move and handle it (or drop it)
     * appropriately.
     *
     * @param mailbox the actual mailbox
     */
    private static void processUploadMessage(Context context, Store remoteStore, Mailbox mailbox,
            long messageId, boolean manualSync)
            throws MessagingException {
        EmailContent.Message newMessage =
                EmailContent.Message.restoreMessageWithId(context, messageId);
        final boolean deleteUpdate;
        if (newMessage == null) {
            deleteUpdate = true;
            LogUtils.d(Logging.LOG_TAG, "Upsync failed for null message, id=" + messageId);
        } else if (mailbox.mType == Mailbox.TYPE_DRAFTS) {
            deleteUpdate = false;
            LogUtils.d(Logging.LOG_TAG, "Upsync skipped for mailbox=drafts, id=" + messageId);
        } else if (mailbox.mType == Mailbox.TYPE_OUTBOX) {
            deleteUpdate = false;
            LogUtils.d(Logging.LOG_TAG, "Upsync skipped for mailbox=outbox, id=" + messageId);
        } else if (mailbox.mType == Mailbox.TYPE_TRASH) {
            deleteUpdate = false;
            LogUtils.d(Logging.LOG_TAG, "Upsync skipped for mailbox=trash, id=" + messageId);
        } else if (newMessage.mMailboxKey != mailbox.mId) {
            deleteUpdate = false;
            LogUtils.d(Logging.LOG_TAG, "Upsync skipped; mailbox changed, id=" + messageId);
        } else {
            LogUtils.d(Logging.LOG_TAG, "Upsync triggered for message id=" + messageId);
            deleteUpdate =
                    processPendingAppend(context, remoteStore, mailbox, newMessage, manualSync);
        }
        if (deleteUpdate) {
            // Finally, delete the update (if any)
            Uri uri = ContentUris.withAppendedId(
                    EmailContent.Message.UPDATED_CONTENT_URI, messageId);
            context.getContentResolver().delete(uri, null, null);
        }
    }

    /**
     * Upsync changes to read, flagged, or mailbox
     *
     * @param remoteStore the remote store for this mailbox
     * @param mailbox the mailbox the message is stored in
     * @param changeRead whether the message's read state has changed
     * @param changeFlagged whether the message's flagged state has changed
     * @param changeMailbox whether the message's mailbox has changed
     * @param oldMessage the message in it's pre-change state
     * @param newMessage the current version of the message
     */
    private static void processPendingDataChange(final Context context, Store remoteStore,
            Mailbox mailbox, boolean changeRead, boolean changeFlagged, boolean changeMailbox,
            boolean changeAnswered, EmailContent.Message oldMessage,
            final EmailContent.Message newMessage) throws MessagingException {
        // New mailbox is the mailbox this message WILL be in (same as the one it WAS in if it isn't
        // being moved
        Mailbox newMailbox = mailbox;
        // Mailbox is the original remote mailbox (the one we're acting on)
        mailbox = getRemoteMailboxForMessage(context, oldMessage);

        // 0. No remote update if the message is local-only
        if (newMessage.mServerId == null || newMessage.mServerId.equals("")
                || newMessage.mServerId.startsWith(LOCAL_SERVERID_PREFIX) || (mailbox == null)) {
            return;
        }

        // 1. No remote update for DRAFTS or OUTBOX
        if (mailbox.mType == Mailbox.TYPE_DRAFTS || mailbox.mType == Mailbox.TYPE_OUTBOX) {
            return;
        }

        // 2. Open the remote store & folder
        Folder remoteFolder = remoteStore.getFolder(mailbox.mServerId);
        if (!remoteFolder.exists()) {
            return;
        }
        remoteFolder.open(OpenMode.READ_WRITE);
        if (remoteFolder.getMode() != OpenMode.READ_WRITE) {
            return;
        }

        // 3. Finally, apply the changes to the message
        Message remoteMessage = remoteFolder.getMessage(newMessage.mServerId);
        if (remoteMessage == null) {
            return;
        }
        if (DebugUtils.DEBUG) {
            LogUtils.d(Logging.LOG_TAG,
                    "Update for msg id=" + newMessage.mId
                    + " read=" + newMessage.mFlagRead
                    + " flagged=" + newMessage.mFlagFavorite
                    + " answered="
                    + ((newMessage.mFlags & EmailContent.Message.FLAG_REPLIED_TO) != 0)
                    + " new mailbox=" + newMessage.mMailboxKey);
        }
        Message[] messages = new Message[] { remoteMessage };
        if (changeRead) {
            remoteFolder.setFlags(messages, FLAG_LIST_SEEN, newMessage.mFlagRead);
        }
        if (changeFlagged) {
            remoteFolder.setFlags(messages, FLAG_LIST_FLAGGED, newMessage.mFlagFavorite);
        }
        if (changeAnswered) {
            remoteFolder.setFlags(messages, FLAG_LIST_ANSWERED,
                    (newMessage.mFlags & EmailContent.Message.FLAG_REPLIED_TO) != 0);
        }
        if (changeMailbox) {
            Folder toFolder = remoteStore.getFolder(newMailbox.mServerId);
            if (!remoteFolder.exists()) {
                return;
            }
            // We may need the message id to search for the message in the destination folder
            remoteMessage.setMessageId(newMessage.mMessageId);
            // Copy the message to its new folder
            remoteFolder.copyMessages(messages, toFolder, new MessageUpdateCallbacks() {
                @Override
                public void onMessageUidChange(Message message, String newUid) {
                    ContentValues cv = new ContentValues();
                    cv.put(MessageColumns.SERVER_ID, newUid);
                    // We only have one message, so, any updates _must_ be for it. Otherwise,
                    // we'd have to cycle through to find the one with the same server ID.
                    context.getContentResolver().update(ContentUris.withAppendedId(
                            EmailContent.Message.CONTENT_URI, newMessage.mId), cv, null, null);
                }

                @Override
                public void onMessageNotFound(Message message) {
                }
            });
            // Delete the message from the remote source folder
            remoteMessage.setFlag(Flag.DELETED, true);
            remoteFolder.expunge();
        }
        remoteFolder.close(false);
    }

    /**
     * Process a pending trash message command.
     *
     * @param remoteStore the remote store we're working in
     * @param newMailbox The local trash mailbox
     * @param oldMessage The message copy that was saved in the updates shadow table
     * @param newMessage The message that was moved to the mailbox
     */
    private static void processPendingMoveToTrash(final Context context, Store remoteStore,
            Mailbox newMailbox, EmailContent.Message oldMessage,
            final EmailContent.Message newMessage) throws MessagingException {

        // 0. No remote move if the message is local-only
        if (newMessage.mServerId == null || newMessage.mServerId.equals("")
                || newMessage.mServerId.startsWith(LOCAL_SERVERID_PREFIX)) {
            return;
        }

        // 1. Escape early if we can't find the local mailbox
        // TODO smaller projection here
        Mailbox oldMailbox = getRemoteMailboxForMessage(context, oldMessage);
        if (oldMailbox == null) {
            // can't find old mailbox, it may have been deleted.  just return.
            return;
        }
        // 2. We don't support delete-from-trash here
        if (oldMailbox.mType == Mailbox.TYPE_TRASH) {
            return;
        }

        // The rest of this method handles server-side deletion

        // 4.  Find the remote mailbox (that we deleted from), and open it
        Folder remoteFolder = remoteStore.getFolder(oldMailbox.mServerId);
        if (!remoteFolder.exists()) {
            return;
        }

        remoteFolder.open(OpenMode.READ_WRITE);
        if (remoteFolder.getMode() != OpenMode.READ_WRITE) {
            remoteFolder.close(false);
            return;
        }

        // 5. Find the remote original message
        Message remoteMessage = remoteFolder.getMessage(oldMessage.mServerId);
        if (remoteMessage == null) {
            remoteFolder.close(false);
            return;
        }

        // 6. Find the remote trash folder, and create it if not found
        Folder remoteTrashFolder = remoteStore.getFolder(newMailbox.mServerId);
        if (!remoteTrashFolder.exists()) {
            /*
             * If the remote trash folder doesn't exist we try to create it.
             */
            remoteTrashFolder.create(FolderType.HOLDS_MESSAGES);
        }

        // 7. Try to copy the message into the remote trash folder
        // Note, this entire section will be skipped for POP3 because there's no remote trash
        if (remoteTrashFolder.exists()) {
            /*
             * Because remoteTrashFolder may be new, we need to explicitly open it
             */
            remoteTrashFolder.open(OpenMode.READ_WRITE);
            if (remoteTrashFolder.getMode() != OpenMode.READ_WRITE) {
                remoteFolder.close(false);
                remoteTrashFolder.close(false);
                return;
            }

            remoteFolder.copyMessages(new Message[] { remoteMessage }, remoteTrashFolder,
                    new Folder.MessageUpdateCallbacks() {
                @Override
                public void onMessageUidChange(Message message, String newUid) {
                    // update the UID in the local trash folder, because some stores will
                    // have to change it when copying to remoteTrashFolder
                    ContentValues cv = new ContentValues();
                    cv.put(MessageColumns.SERVER_ID, newUid);
                    context.getContentResolver().update(newMessage.getUri(), cv, null, null);
                }

                /**
                 * This will be called if the deleted message doesn't exist and can't be
                 * deleted (e.g. it was already deleted from the server.)  In this case,
                 * attempt to delete the local copy as well.
                 */
                @Override
                public void onMessageNotFound(Message message) {
                    context.getContentResolver().delete(newMessage.getUri(), null, null);
                }
            });
            remoteTrashFolder.close(false);
        }

        // 8. Delete the message from the remote source folder
        remoteMessage.setFlag(Flag.DELETED, true);
        remoteFolder.expunge();
        remoteFolder.close(false);
    }

    /**
     * Process a pending trash message command.
     *
     * @param remoteStore the remote store we're working in
     * @param oldMailbox The local trash mailbox
     * @param oldMessage The message that was deleted from the trash
     */
    private static void processPendingDeleteFromTrash(Store remoteStore,
            Mailbox oldMailbox, EmailContent.Message oldMessage)
            throws MessagingException {

        // 1. We only support delete-from-trash here
        if (oldMailbox.mType != Mailbox.TYPE_TRASH) {
            return;
        }

        // 2.  Find the remote trash folder (that we are deleting from), and open it
        Folder remoteTrashFolder = remoteStore.getFolder(oldMailbox.mServerId);
        if (!remoteTrashFolder.exists()) {
            return;
        }

        remoteTrashFolder.open(OpenMode.READ_WRITE);
        if (remoteTrashFolder.getMode() != OpenMode.READ_WRITE) {
            remoteTrashFolder.close(false);
            return;
        }

        // 3. Find the remote original message
        Message remoteMessage = remoteTrashFolder.getMessage(oldMessage.mServerId);
        if (remoteMessage == null) {
            remoteTrashFolder.close(false);
            return;
        }

        // 4. Delete the message from the remote trash folder
        remoteMessage.setFlag(Flag.DELETED, true);
        remoteTrashFolder.expunge();
        remoteTrashFolder.close(false);
    }

    /**
     * Process a pending append message command. This command uploads a local message to the
     * server, first checking to be sure that the server message is not newer than
     * the local message.
     *
     * @param remoteStore the remote store we're working in
     * @param mailbox The mailbox we're appending to
     * @param message The message we're appending
     * @param manualSync True if this is a manual sync (changes upsync behavior)
     * @return true if successfully uploaded
     */
    private static boolean processPendingAppend(Context context, Store remoteStore, Mailbox mailbox,
            EmailContent.Message message, boolean manualSync)
            throws MessagingException {
        boolean updateInternalDate = false;
        boolean updateMessage = false;
        boolean deleteMessage = false;

        // 1. Find the remote folder that we're appending to and create and/or open it
        Folder remoteFolder = remoteStore.getFolder(mailbox.mServerId);
        if (!remoteFolder.exists()) {
            if (!remoteFolder.create(FolderType.HOLDS_MESSAGES)) {
                // This is a (hopefully) transient error and we return false to try again later
                return false;
            }
        }
        remoteFolder.open(OpenMode.READ_WRITE);
        if (remoteFolder.getMode() != OpenMode.READ_WRITE) {
            return false;
        }

        // 2. If possible, load a remote message with the matching UID
        Message remoteMessage = null;
        if (message.mServerId != null && message.mServerId.length() > 0) {
            remoteMessage = remoteFolder.getMessage(message.mServerId);
        }

        // 3. If a remote message could not be found, upload our local message
        if (remoteMessage == null) {
            // TODO:
            // if we have a serverId and remoteMessage is still null, then probably the message
            // has been deleted and we should delete locally.
            // 3a. Create a legacy message to upload
            Message localMessage = LegacyConversions.makeMessage(context, message);
            // 3b. Upload it
            //FetchProfile fp = new FetchProfile();
            //fp.add(FetchProfile.Item.BODY);
            // Note that this operation will assign the Uid to localMessage
            remoteFolder.appendMessage(context, localMessage, manualSync /* no timeout */);

            // 3b. And record the UID from the server
            message.mServerId = localMessage.getUid();
            updateInternalDate = true;
            updateMessage = true;
        } else {
            // 4. If the remote message exists we need to determine which copy to keep.
            // TODO:
            // I don't see a good reason we should be here. If the message already has a serverId,
            // then we should be handling it in processPendingUpdates(),
            // not processPendingUploads()
            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.ENVELOPE);
            remoteFolder.fetch(new Message[] { remoteMessage }, fp, null);
            Date localDate = new Date(message.mServerTimeStamp);
            Date remoteDate = remoteMessage.getInternalDate();
            if (remoteDate != null && remoteDate.compareTo(localDate) > 0) {
                // 4a. If the remote message is newer than ours we'll just
                // delete ours and move on. A sync will get the server message
                // if we need to be able to see it.
                deleteMessage = true;
            } else {
                // 4b. Otherwise we'll upload our message and then delete the remote message.

                // Create a legacy message to upload
                // TODO: This strategy has a problem: This will create a second message,
                // so that at least temporarily, we will have two messages for what the
                // user would think of as one.
                Message localMessage = LegacyConversions.makeMessage(context, message);

                // 4c. Upload it
                fp.clear();
                fp = new FetchProfile();
                fp.add(FetchProfile.Item.BODY);
                remoteFolder.appendMessage(context, localMessage, manualSync /* no timeout */);

                // 4d. Record the UID and new internalDate from the server
                message.mServerId = localMessage.getUid();
                updateInternalDate = true;
                updateMessage = true;

                // 4e. And delete the old copy of the message from the server.
                remoteMessage.setFlag(Flag.DELETED, true);
            }
        }

        // 5. If requested, Best-effort to capture new "internaldate" from the server
        if (updateInternalDate && message.mServerId != null) {
            try {
                Message remoteMessage2 = remoteFolder.getMessage(message.mServerId);
                if (remoteMessage2 != null) {
                    FetchProfile fp2 = new FetchProfile();
                    fp2.add(FetchProfile.Item.ENVELOPE);
                    remoteFolder.fetch(new Message[] { remoteMessage2 }, fp2, null);
                    final Date remoteDate = remoteMessage2.getInternalDate();
                    if (remoteDate != null) {
                        message.mServerTimeStamp = remoteMessage2.getInternalDate().getTime();
                        updateMessage = true;
                    }
                }
            } catch (MessagingException me) {
                // skip it - we can live without this
            }
        }

        // 6. Perform required edits to local copy of message
        if (deleteMessage || updateMessage) {
            Uri uri = ContentUris.withAppendedId(EmailContent.Message.CONTENT_URI, message.mId);
            ContentResolver resolver = context.getContentResolver();
            if (deleteMessage) {
                resolver.delete(uri, null, null);
            } else if (updateMessage) {
                ContentValues cv = new ContentValues();
                cv.put(MessageColumns.SERVER_ID, message.mServerId);
                cv.put(MessageColumns.SERVER_TIMESTAMP, message.mServerTimeStamp);
                resolver.update(uri, cv, null, null);
            }
        }

        return true;
    }

    /**
     * A message and numeric uid that's easily sortable
     */
    private static class SortableMessage {
        private final Message mMessage;
        private final long mUid;

        SortableMessage(Message message, long uid) {
            mMessage = message;
            mUid = uid;
        }
    }

    private static int searchMailboxImpl(final Context context, final long accountId,
            final SearchParams searchParams, final long destMailboxId) throws MessagingException {
        final Account account = Account.restoreAccountWithId(context, accountId);
        final Mailbox mailbox = Mailbox.restoreMailboxWithId(context, searchParams.mMailboxId);
        final Mailbox destMailbox = Mailbox.restoreMailboxWithId(context, destMailboxId);
        if (account == null || mailbox == null || destMailbox == null) {
            LogUtils.d(Logging.LOG_TAG, "Attempted search for %s "
                    + "but account or mailbox information was missing", searchParams);
            return 0;
        }

        // Tell UI that we're loading messages
        final ContentValues statusValues = new ContentValues(2);
        statusValues.put(Mailbox.UI_SYNC_STATUS, UIProvider.SyncStatus.LIVE_QUERY);
        destMailbox.update(context, statusValues);

        Store remoteStore = null;
        int numSearchResults = 0;
        try {
            remoteStore = Store.getInstance(account, context);
            final Folder remoteFolder = remoteStore.getFolder(mailbox.mServerId);
            remoteFolder.open(OpenMode.READ_WRITE);

            SortableMessage[] sortableMessages = new SortableMessage[0];
            if (searchParams.mOffset == 0) {
                // Get the "bare" messages (basically uid)
                final Message[] remoteMessages = remoteFolder.getMessages(searchParams, null);
                final int remoteCount = remoteMessages.length;
                if (remoteCount > 0) {
                    sortableMessages = new SortableMessage[remoteCount];
                    int i = 0;
                    for (Message msg : remoteMessages) {
                        sortableMessages[i++] = new SortableMessage(msg,
                                Long.parseLong(msg.getUid()));
                    }
                    // Sort the uid's, most recent first
                    // Note: Not all servers will be nice and return results in the order of
                    // request; those that do will see messages arrive from newest to oldest
                    Arrays.sort(sortableMessages, new Comparator<SortableMessage>() {
                        @Override
                        public int compare(SortableMessage lhs, SortableMessage rhs) {
                            return lhs.mUid > rhs.mUid ? -1 : lhs.mUid < rhs.mUid ? 1 : 0;
                        }
                    });
                    sSearchResults.put(accountId, sortableMessages);
                }
            } else {
                // It seems odd for this to happen, but if the previous query returned zero results,
                // but the UI somehow still attempted to load more, then sSearchResults will have
                // a null value for this account. We need to handle this below.
                sortableMessages = sSearchResults.get(accountId);
            }

            numSearchResults = (sortableMessages != null ? sortableMessages.length : 0);
            final int numToLoad =
                    Math.min(numSearchResults - searchParams.mOffset, searchParams.mLimit);
            destMailbox.updateMessageCount(context, numSearchResults);
            if (numToLoad <= 0) {
                return 0;
            }

            final ArrayList<Message> messageList = new ArrayList<>();
            for (int i = searchParams.mOffset; i < numToLoad + searchParams.mOffset; i++) {
                messageList.add(sortableMessages[i].mMessage);
            }
            // First fetch FLAGS and ENVELOPE. In a second pass, we'll fetch STRUCTURE and
            // the first body part.
            final FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.FLAGS);
            fp.add(FetchProfile.Item.ENVELOPE);

            Message[] messageArray = messageList.toArray(new Message[messageList.size()]);

            // TODO: We are purposely processing messages with a MessageRetrievalListener here,
            // rather than just walking the messageArray after the operation completes. This is so
            // that we can immediately update the database so the user can see something useful
            // happening, even if the message body has not yet been fetched.
            // There are some issues with this approach:
            // 1. It means that we have a single thread doing both network and database operations,
            // and either can block the other. The database updates could slow down the network
            // reads, keeping our network connection open longer than is really necessary.
            // 2. We still load all of this data into messageArray, even though it's not used.
            // It would be nicer if we had one thread doing the network operation, and a separate
            // thread consuming that data and performing the appropriate database work, then
            // discarding the data as soon as it is no longer needed. This would reduce our memory
            // footprint and potentially allow our network operation to complete faster.
            remoteFolder.fetch(messageArray, fp, new MessageRetrievalListener() {
                @Override
                public void messageRetrieved(Message message) {
                    try {
                        EmailContent.Message localMessage = new EmailContent.Message();

                        // Copy the fields that are available into the message
                        LegacyConversions.updateMessageFields(localMessage,
                                message, account.mId, mailbox.mId);
                        // Save off the mailbox that this message *really* belongs in.
                        // We need this information if we need to do more lookups
                        // (like loading attachments) for this message. See b/11294681
                        localMessage.mMainMailboxKey = localMessage.mMailboxKey;
                        localMessage.mMailboxKey = destMailboxId;
                        // We load 50k or so; maybe it's complete, maybe not...
                        int flag = EmailContent.Message.FLAG_LOADED_COMPLETE;
                        // We store the serverId of the source mailbox into protocolSearchInfo
                        // This will be used by loadMessageForView, etc. to use the proper remote
                        // folder
                        localMessage.mProtocolSearchInfo = mailbox.mServerId;
                        // Commit the message to the local store
                        Utilities.saveOrUpdate(localMessage, context);
                    } catch (MessagingException me) {
                        LogUtils.e(Logging.LOG_TAG, me,
                                "Error while copying downloaded message.");
                    } catch (Exception e) {
                        LogUtils.e(Logging.LOG_TAG, e,
                                "Error while storing downloaded message.");
                    }
                }

                @Override
                public void loadAttachmentProgress(int progress) {
                }
            });

            // Now load the structure for all of the messages:
            fp.clear();
            fp.add(FetchProfile.Item.STRUCTURE);
            remoteFolder.fetch(messageArray, fp, null);

            // Finally, load the first body part (i.e. message text).
            // This means attachment contents are not yet loaded, but that's okay,
            // we'll load them as needed, same as in synced messages.
            Message[] oneMessageArray = new Message[1];
            for (Message message : messageArray) {
                // Build a list of parts we are interested in. Text parts will be downloaded
                // right now, attachments will be left for later.
                ArrayList<Part> viewables = new ArrayList<>();
                ArrayList<Part> attachments = new ArrayList<>();
                MimeUtility.collectParts(message, viewables, attachments);
                // Download the viewables immediately
                oneMessageArray[0] = message;
                for (Part part : viewables) {
                    fp.clear();
                    fp.add(part);
                    remoteFolder.fetch(oneMessageArray, fp, null);
                }
                // Store the updated message locally and mark it fully loaded
                Utilities.copyOneMessageToProvider(context, message, account, destMailbox,
                        EmailContent.Message.FLAG_LOADED_COMPLETE);
            }

        } finally {
            if (remoteStore != null) {
                remoteStore.closeConnections();
            }
            // Tell UI that we're done loading messages
            statusValues.put(Mailbox.SYNC_TIME, System.currentTimeMillis());
            statusValues.put(Mailbox.UI_SYNC_STATUS, UIProvider.SyncStatus.NO_SYNC);
            destMailbox.update(context, statusValues);
        }

        return numSearchResults;
    }

    private static synchronized void processImapIdleChangesLocked(Context context, Account account,
            Mailbox mailbox, boolean needSync, List<String> fetchMessages) {

        // Process local to server changes first
        Store remoteStore = null;
        try {
            remoteStore = Store.getInstance(account, context);
            processPendingActionsSynchronous(context, account, remoteStore, false);
        } catch (MessagingException me) {
            // Ignore
        } finally {
            if (remoteStore != null) {
                remoteStore.closeConnections();
            }
        }

        // If the request rebased the maximum time without a full sync, then instead of fetch
        // the changes just perform a full sync
        final long timeSinceLastFullSync = SystemClock.elapsedRealtime() -
                mailbox.mLastFullSyncTime;
        final boolean forceSync = timeSinceLastFullSync >= FULL_SYNC_INTERVAL_MILLIS
                || timeSinceLastFullSync < 0;
        if (forceSync) {
            needSync = true;
            fetchMessages.clear();

            if (Logging.LOGD) {
                LogUtils.d(LOG_TAG, "Full sync required for mailbox " + mailbox.mId
                        + " because is exceded the maximum time without a full sync.");
            }
        }

        final int msgToFetchSize = fetchMessages.size();
        if (Logging.LOGD) {
            LogUtils.d(LOG_TAG, "Processing IDLE changes for mailbox " + mailbox.mId
                    + ": need sync " + needSync + ", " + msgToFetchSize + " fetch messages");
        }

        if (msgToFetchSize > 0) {
            if (!needSync && msgToFetchSize <= MAX_MESSAGES_TO_FETCH) {
                try {
                    processImapFetchChanges(context, account, mailbox, fetchMessages);
                } catch (MessagingException ex) {
                    LogUtils.w(LOG_TAG,
                            "Failed to process imap idle changes for mailbox " + mailbox.mId);
                    needSync = true;
                }
            } else {
                needSync = true;
            }
        }

        if (needSync) {
            requestSync(context, account, mailbox.mId, true);
        } else {
            // In case no sync happens, re-add idle status
            try {
                if (account.getSyncInterval() == Account.CHECK_INTERVAL_PUSH) {
                    final ImapIdleFolderHolder holder = ImapIdleFolderHolder.getInstance();
                    holder.registerMailboxForIdle(context, account, mailbox);
                }
            } catch (MessagingException ex) {
                LogUtils.w(LOG_TAG, "Failed to readd imap idle after no sync " +
                        "for mailbox " + mailbox.mId);
            }
        }
    }
}
