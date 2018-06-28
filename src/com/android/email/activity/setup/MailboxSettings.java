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

package com.android.email.activity.setup;

import android.app.ActionBar;
import android.app.Activity;
import android.app.LoaderManager;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.Loader;
import android.content.res.Resources;
import android.database.Cursor;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Vibrator;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceFragment;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.v4.util.Pair;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.email.R;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.provider.Policy;
import com.android.emailcommon.utility.EmailAsyncTask;
import com.android.emailcommon.utility.Utility;
import com.android.mail.preferences.FolderPreferences;
import com.android.mail.providers.Folder;
import com.android.mail.providers.UIProvider;
import com.android.mail.ui.MailAsyncTaskLoader;
import com.android.mail.ui.settings.BasePreferenceActivity;
import com.android.mail.utils.LogUtils;
import com.google.common.base.Preconditions;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Mailbox settings" activity.
 *
 * It's used to update per-mailbox sync settings.  It normally updates Mailbox settings, unless
 * the target mailbox is Inbox, in which case it updates Account settings instead.
 *
 * All changes made by the user will not be immediately saved to the database, as changing the
 * sync window may result in removal of messages.  Instead, we only save to the database in {@link
 * #onDestroy()}, unless it's called for configuration changes.
 */
public class MailboxSettings extends BasePreferenceActivity {
    public static final String PREFERENCE_SYNC_SETTINGS = "account_sync_settings";
    public static final String PREFERENCE_PER_FOLDER_NOTIFICATIONS =
            "account_per_folder_notifications";

    private static final String EXTRA_FOLDERS_URI = "FOLDERS_URI";
    private static final String EXTRA_ACCOUNT_EMAIL = "ACCOUNT_EMAIL";
    private static final String EXTRA_INBOX_ID = "INBOX_ID";
    private static final String EXTRA_TYPE = "TYPE";

    private static final String EXTRA_HEADER_FOLDER_INDENT = "folder-indent";
    private static final String EXTRA_HEADER_IS_CHECKED = "is-checked";

    private static final int FOLDERS_LOADER_ID = 0;
    private Uri mFoldersUri;
    private int mInboxId;
    private String mType;
    private final List<FolderInfo> mFolders = new ArrayList<>();

    /**
     * Starts the activity
     */
    public static Intent getIntent(Context context, com.android.mail.providers.Account uiAccount,
            Folder inbox, String type) {
        final Intent i = new Intent(context, MailboxSettings.class);
        i.putExtra(EXTRA_FOLDERS_URI, uiAccount.fullFolderListUri);
        i.putExtra(EXTRA_ACCOUNT_EMAIL, uiAccount.getEmailAddress());
        i.putExtra(EXTRA_INBOX_ID, inbox.id);
        i.putExtra(EXTRA_TYPE, type);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // This needs to happen before super.onCreate() since that calls onBuildHeaders()
        mInboxId = getIntent().getIntExtra(EXTRA_INBOX_ID, -1);
        mFoldersUri = getIntent().getParcelableExtra(EXTRA_FOLDERS_URI);
        mType = getIntent().getStringExtra(EXTRA_TYPE);

        if (mFoldersUri != null) {
            getLoaderManager().initLoader(FOLDERS_LOADER_ID, null,
                    new MailboxSettingsFolderLoaderCallbacks());
        }

        if (mType != null && mType.equals(PREFERENCE_SYNC_SETTINGS)) {
            setTitle(getString(R.string.mailbox_settings_activity_title));
        } else if (mType != null && mType.equals(PREFERENCE_PER_FOLDER_NOTIFICATIONS)) {
            setTitle(getString(R.string.mailbox_notify_settings_activity_title));
        }

        super.onCreate(savedInstanceState);
    }

    @Override
    public void showBreadCrumbs(CharSequence title, CharSequence shortTitle) {
        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setTitle(title);
            actionBar.setSubtitle(shortTitle);
        }
    }

    @Override
    public void onBuildHeaders(List<Header> target) {
        if (mFolders.isEmpty()) {
            final Header dummy = new Header();
            dummy.titleRes = R.string.account_waiting_for_folders_msg;
            target.add(dummy);
        } else {
            final String accountEmail = getIntent().getStringExtra(EXTRA_ACCOUNT_EMAIL);
            for (final FolderInfo f : mFolders) {
                final Header h = new Header();
                h.title = f.folder.name;
                setMailboxHeaderIcon(h, f.folder);
                h.extras = new Bundle();
                h.extras.putInt(EXTRA_HEADER_FOLDER_INDENT, f.fullFolderName.split("\\/").length - 1);
                if (mType != null && mType.equals(PREFERENCE_SYNC_SETTINGS)) {
                    h.fragment = MailboxSettingsFragment.class.getName();
                    h.fragmentArguments = MailboxSettingsFragment.getArguments(f);
                    h.breadCrumbTitle = f.folder.name;
                    h.breadCrumbShortTitleRes = R.string.mailbox_settings_activity_title;
                    h.extras.putBoolean(EXTRA_HEADER_IS_CHECKED, f.mailbox.mSyncInterval != 0);
                } else if (mType != null && mType.equals(PREFERENCE_PER_FOLDER_NOTIFICATIONS)) {
                    h.fragment = MailboxNotificationsFragment.class.getName();
                    h.fragmentArguments = MailboxNotificationsFragment.getArguments(f, accountEmail);
                    h.breadCrumbTitle = f.folder.name;
                    h.breadCrumbShortTitleRes = R.string.mailbox_notify_settings_activity_title;

                    final FolderPreferences prefs = new FolderPreferences(this,
                            accountEmail, f.folder, f.folder.isInbox());
                    h.extras.putBoolean(EXTRA_HEADER_IS_CHECKED, prefs.areNotificationsEnabled());
                }
                target.add(h);
            }
        }

        setListAdapter(new MailboxHeadersAdapter(this, target));
    }

    @Override
    protected boolean isValidFragment(String fragmentName) {
        // Activity is not exported
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void setMailboxHeaderIcon(Header header, Folder folder) {
        if (folder.isSent()) {
            header.iconRes = R.drawable.ic_drawer_sent_24dp;
        } else if (folder.isInbox()) {
            header.iconRes = R.drawable.ic_drawer_inbox_24dp;
        } else {
            header.iconRes = folder.hasChildren ? R.drawable.ic_folder_parent_24dp
                : R.drawable.ic_drawer_folder_24dp;
        }
    }

    private void onMailboxSyncIntervalChanged(Mailbox mailbox, int value) {
        for (FolderInfo info : mFolders) {
            if (info.mailbox.mId == mailbox.mId) {
                info.mailbox.mSyncInterval = value;
                break;
            }
        }
        invalidateHeaders();
    }

    private void onMailboxSyncLookbackChanged(Mailbox mailbox, int value) {
        for (FolderInfo info : mFolders) {
            if (info.mailbox.mId == mailbox.mId) {
                info.mailbox.mSyncLookback = value;
                break;
            }
        }
    }

    /**
     * Setup the entries and entry values for the sync lookback preference
     * @param context the caller's context
     * @param pref a ListPreference to be set up
     * @param maxLookback The maximum lookback allowed, or 0 if no max.
     * @param showWithDefault Whether to show the version with default, or without.
     */
    public static void setupLookbackPreferenceOptions(final Context context,
            final ListPreference pref, final int maxLookback, final boolean showWithDefault) {
        final Resources resources = context.getResources();
        // Load the complete list of entries/values
        CharSequence[] entries;
        CharSequence[] values;
        final int offset;
        if (showWithDefault) {
            entries = resources.getTextArray(
                    R.array.account_settings_mail_window_entries_with_default);
            values = resources.getTextArray(
                    R.array.account_settings_mail_window_values_with_default);
            offset = 1;
        } else {
            entries = resources.getTextArray(R.array.account_settings_mail_window_entries);
            values = resources.getTextArray(R.array.account_settings_mail_window_values);
            offset = 0;
        }
        // If we have a maximum lookback policy, enforce it
        if (maxLookback > 0) {
            final int size = maxLookback + offset;
            entries = Arrays.copyOf(entries, size);
            values = Arrays.copyOf(values, size);
        }
        // Set up the preference
        pref.setEntries(entries);
        pref.setEntryValues(values);
        pref.setSummary(pref.getEntry());
    }

    private static class MailboxHeadersAdapter extends ArrayAdapter<Header> {
        private static class HeaderViewHolder {
            View spacer;
            ImageView icon;
            TextView title;
            View checkmark;
        }

        private LayoutInflater mInflater;
        private int mFolderIndent;

        public MailboxHeadersAdapter(Context context, List<Header> objects) {
            super(context, 0, objects);
            mInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            mFolderIndent = (int) context.getResources().getDimension(R.dimen.child_folder_indent);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            HeaderViewHolder holder;
            View view;

            if (convertView == null) {
                view = mInflater.inflate(R.layout.preference_mailbox_item, parent, false);
                holder = new HeaderViewHolder();
                holder.spacer = view.findViewById(R.id.spacer);
                holder.icon = (ImageView) view.findViewById(R.id.icon);
                holder.title = (TextView) view.findViewById(android.R.id.title);
                holder.checkmark = view.findViewById(R.id.checkmark);
                view.setTag(holder);
            } else {
                view = convertView;
                holder = (HeaderViewHolder) view.getTag();
            }

            // All view fields must be updated every time, because the view may be recycled
            Header header = getItem(position);
            int headerIndent = header.extras.getInt(EXTRA_HEADER_FOLDER_INDENT, 0);
            boolean isChecked = header.extras.getBoolean(EXTRA_HEADER_IS_CHECKED, false);
            holder.spacer.getLayoutParams().width = mFolderIndent * headerIndent;
            holder.icon.setImageResource(header.iconRes);
            holder.title.setText(header.getTitle(getContext().getResources()));
            holder.checkmark.setVisibility(isChecked ? View.VISIBLE : View.INVISIBLE);
            return view;
        }
    }

    private static class FolderInfo {
        Folder folder;
        Mailbox mailbox;
        String fullFolderName;
    }

    private static class FolderLoader extends MailAsyncTaskLoader<List<FolderInfo>> {
        private Uri mFoldersUri;

        public FolderLoader(Context context, Uri uri) {
            super(context);
            mFoldersUri = uri;
        }

        @Override
        public List<FolderInfo> loadInBackground() {
            Cursor c = getContext().getContentResolver().query(mFoldersUri,
                    UIProvider.FOLDERS_PROJECTION, null, null, null);
            if (c == null) {
                return null;
            }

            List<FolderInfo> result = new ArrayList<>();
            c.moveToFirst();
            while (!c.isAfterLast()) {
                FolderInfo info = new FolderInfo();
                info.folder = new Folder(c);
                info.mailbox = Mailbox.restoreMailboxWithId(getContext(), info.folder.id);
                if (info.mailbox != null) {
                    result.add(info);
                }
                c.moveToNext();
            }
            c.close();

            return result;
        }

        @Override
        protected void onDiscardResult(List<FolderInfo> result) {}
    }

    private class MailboxSettingsFolderLoaderCallbacks
            implements LoaderManager.LoaderCallbacks<List<FolderInfo>> {

        @Override
        public Loader<List<FolderInfo>> onCreateLoader(int i, Bundle bundle) {
            return new FolderLoader(MailboxSettings.this, mFoldersUri);
        }

        @Override
        public void onLoadFinished(Loader<List<FolderInfo>> loader, List<FolderInfo> result) {
            mFolders.clear();

            if (result == null) {
                return;
            }

            // Convert the cursor to an temp array and map all the folders
            Map<Uri, Folder> folders = new HashMap<>();
            List<FolderInfo> tmp = new ArrayList<>();
            Folder inbox = null;
            Folder sent = null;
            for (FolderInfo info : result) {
                final Folder folder = info.folder;
                if (!folder.supportsCapability(UIProvider.FolderCapabilities.IS_VIRTUAL) &&
                        !folder.isTrash() && !folder.isDraft() && !folder.isOutbox()) {
                    if (folder.id == mInboxId) {
                        inbox = folder;
                    } else if (folder.isSent()) {
                        sent = folder;
                    }
                    tmp.add(info);
                    folders.put(folder.folderUri.fullUri, folder);
                }
            }

            // Create the hierarchical paths of all the folders
            int count = tmp.size();
            for (int i = 0; i < count; i++) {
                FolderInfo info = tmp.get(i);
                info.fullFolderName = getHierarchicalFolder(info.folder, folders);
                mFolders.add(info);
            }

            // Sort folders by hierarchical path
            final String inboxFolderName = inbox.name;
            final String sentFolderName = sent.name;
            Collections.sort(mFolders, new Comparator<FolderInfo>() {
                private final Collator mCollator = Collator.getInstance();
                @Override
                public int compare(FolderInfo lhs, FolderInfo rhs) {
                    boolean lInbox = lhs.fullFolderName.startsWith(inboxFolderName);
                    boolean rInbox = rhs.fullFolderName.startsWith(inboxFolderName);
                    boolean lSent = lhs.fullFolderName.startsWith(sentFolderName);
                    boolean rSent = rhs.fullFolderName.startsWith(sentFolderName);
                    String lParent = getHierarchicalParentFolder(lhs.fullFolderName);
                    String rParent = getHierarchicalParentFolder(rhs.fullFolderName);
                    if (lInbox && !rInbox) {
                        return -1;
                    } else if (!lInbox && rInbox) {
                        return 1;
                    }
                    if (lSent && !rSent) {
                        return -1;
                    } else if (!lSent && rSent) {
                        return 1;
                    }
                    if (lhs.fullFolderName.startsWith(rhs.fullFolderName)) {
                        return 1;
                    }
                    if (rhs.fullFolderName.startsWith(lhs.fullFolderName)) {
                        return -1;
                    }
                    if (lParent != null && rParent != null && lParent.equals(rParent)) {
                        return mCollator.compare(lhs.folder.name, rhs.folder.name);
                    }
                    return mCollator.compare(lhs.fullFolderName, rhs.fullFolderName);
                }
            });

            invalidateHeaders();
        }

        @Override
        public void onLoaderReset(Loader<List<FolderInfo>> loader) {
            mFolders.clear();
        }

        private String getHierarchicalFolder(Folder folder, Map<Uri, Folder> folders) {
            if (!TextUtils.isEmpty(folder.hierarchicalDesc)) {
                return folder.hierarchicalDesc;
            }
            String name = folder.name;
            Folder tmp = folder;
            while (tmp != null && tmp.parent != null && !tmp.parent.toString().isEmpty()) {
                tmp = folders.get(tmp.parent);
                if (tmp != null) {
                    name = tmp.name + "/" + name;
                }
            }
            return name;
        }

        private String getHierarchicalParentFolder(String folder) {
            int pos = folder.lastIndexOf("/");
            if (pos != -1) {
                return folder.substring(0, pos);
            }
            return null;
        }
    }

    public static class MailboxNotificationsFragment extends PreferenceFragment {
        private static final String EXTRA_FOLDER = "Folder";
        private static final String EXTRA_ACCOUNT_EMAIL = "AccountEmail";
        private static final String EXTRA_MAILBOX_TYPE = "MailboxType";

        private static final String PREF_NOTIF_ENABLED_KEY = "notifications-enabled";
        private static final String PREF_NOTIF_RINGTONE_KEY = "notification-ringtone";
        private static final String PREF_NOTIF_VIBRATE_KEY = "notification-vibrate";

        private static final int RINGTONE_REQUEST_CODE =
                MailboxNotificationsFragment.class.hashCode();

        private FolderPreferences mPreferences;
        private Account mAccount;
        private Mailbox mMailbox;

        private CheckBoxPreference mPrefNotifEnabled;
        private Preference mPrefNotifRingtone;
        private CheckBoxPreference mPrefNotifVibrate;

        private Uri mRingtoneUri;
        private Ringtone mRingtone;

        private static Bundle getArguments(FolderInfo info, String accountEmailAddress) {
            final Bundle b = new Bundle(2);
            b.putParcelable(EXTRA_FOLDER, info.folder);
            b.putString(EXTRA_ACCOUNT_EMAIL, accountEmailAddress);
            b.putInt(EXTRA_MAILBOX_TYPE, info.mailbox.mType);
            return b;
        }

        public MailboxNotificationsFragment() {}

        @Override
        public void onActivityCreated(Bundle savedInstanceState) {
            super.onActivityCreated(savedInstanceState);

            final Folder folder = getArguments().getParcelable(EXTRA_FOLDER);
            final String accountEmail = getArguments().getString(EXTRA_ACCOUNT_EMAIL);
            final int mailboxType = getArguments().getInt(EXTRA_MAILBOX_TYPE, 0);

            mPreferences = new FolderPreferences(getActivity(),
                    accountEmail, folder, folder.isInbox());

            addPreferencesFromResource(R.xml.mailbox_notifications_preferences);

            mPrefNotifEnabled = (CheckBoxPreference) findPreference(PREF_NOTIF_ENABLED_KEY);
            mPrefNotifEnabled.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    mPreferences.setNotificationsEnabled((Boolean) newValue);
                    // update checkmark in header list
                    ((PreferenceActivity) getActivity()).invalidateHeaders();
                    return true;
                }
            });
            mPrefNotifRingtone = findPreference(PREF_NOTIF_RINGTONE_KEY);
            mPrefNotifRingtone.setOnPreferenceClickListener(new OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    showRingtonePicker();
                    return true;
                }
            });
            mPrefNotifVibrate = (CheckBoxPreference) findPreference(PREF_NOTIF_VIBRATE_KEY);
            Vibrator vibrator = (Vibrator) getActivity().getSystemService(Context.VIBRATOR_SERVICE);
            if (!vibrator.hasVibrator()) {
                mPrefNotifVibrate.setChecked(false);
                getPreferenceScreen().removePreference(mPrefNotifVibrate);
                mPrefNotifVibrate = null;
            } else {
                mPrefNotifVibrate.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
                    @Override
                    public boolean onPreferenceChange(Preference preference, Object newValue) {
                        mPreferences.setNotificationVibrateEnabled((Boolean) newValue);
                        return true;
                    }
                });
            }

            boolean enable = mailboxType != Mailbox.TYPE_DRAFTS;

            mPrefNotifEnabled.setChecked(mPreferences.areNotificationsEnabled());
            mPrefNotifEnabled.setEnabled(enable);
            setRingtone(mPreferences.getNotificationRingtoneUri());
            mPrefNotifRingtone.setEnabled(enable);
            if (mPrefNotifVibrate != null) {
                mPrefNotifVibrate.setChecked(mPreferences.isNotificationVibrateEnabled());
                mPrefNotifVibrate.setEnabled(enable);
            }
        }

        private void setRingtone(String ringtone) {
            if (!TextUtils.isEmpty(ringtone)) {
                mRingtoneUri = Uri.parse(ringtone);
                mRingtone = RingtoneManager.getRingtone(getActivity(), mRingtoneUri);
            } else {
                mRingtoneUri = null;
                mRingtone = null;
            }
            setRingtoneSummary();
        }

        private void setRingtoneSummary() {
            final String summary = mRingtone != null ? mRingtone.getTitle(getActivity())
                    : getString(R.string.silent_ringtone);
            mPrefNotifRingtone.setSummary(summary);
        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {
            if (requestCode == RINGTONE_REQUEST_CODE) {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    Uri uri = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
                    String ringtone = "";
                    if (uri != null) {
                        ringtone = uri.toString();
                    }
                    mPreferences.setNotificationRingtoneUri(ringtone);
                    setRingtone(ringtone);
                }
            }
        }

        /**
         * Shows the system ringtone picker.
         */
        private void showRingtonePicker() {
            Intent intent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
            final String ringtoneUri = mPreferences.getNotificationRingtoneUri();
            if (!TextUtils.isEmpty(ringtoneUri)) {
                intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                        Uri.parse(ringtoneUri));
            }
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                    Settings.System.DEFAULT_NOTIFICATION_URI);
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true);
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE,
                    RingtoneManager.TYPE_NOTIFICATION);
            startActivityForResult(intent, RINGTONE_REQUEST_CODE);
        }
    }

    public static class MailboxSettingsFragment extends PreferenceFragment {
        private static final String EXTRA_MAILBOX = "Mailbox";

        private static final String BUNDLE_MAX_LOOKBACK = "MailboxSettings.maxLookback";
        private static final String BUNDLE_SYNC_ENABLED_VALUE = "MailboxSettings.syncEnabled";
        private static final String BUNDLE_SYNC_WINDOW_VALUE = "MailboxSettings.syncWindow";

        private static final String PREF_SYNC_ENABLED_KEY = "sync_enabled";
        private static final String PREF_SYNC_WINDOW_KEY = "sync_window";

        private Mailbox mMailbox;
        /** The maximum lookback allowed for this mailbox, or 0 if no max. */
        private int mMaxLookback;

        private MailboxSettings mActivity;
        private CheckBoxPreference mSyncEnabledPref;
        private ListPreference mSyncLookbackPref;

        private boolean mValuesChanged = false;

        private static Bundle getArguments(FolderInfo info) {
            final Bundle b = new Bundle(1);
            b.putParcelable(EXTRA_MAILBOX, info.mailbox);
            return b;
        }

        public MailboxSettingsFragment() {}

        @Override
        public void onActivityCreated(Bundle savedInstanceState) {
            super.onActivityCreated(savedInstanceState);
            mActivity = (MailboxSettings) getActivity();
            mMailbox = getArguments().getParcelable(EXTRA_MAILBOX);

            addPreferencesFromResource(R.xml.mailbox_preferences);

            mSyncEnabledPref = (CheckBoxPreference) findPreference(PREF_SYNC_ENABLED_KEY);
            mSyncEnabledPref.setOnPreferenceChangeListener(mPreferenceChanged);
            mSyncLookbackPref = (ListPreference) findPreference(PREF_SYNC_WINDOW_KEY);
            mSyncLookbackPref.setOnPreferenceChangeListener(mPreferenceChanged);

            if (savedInstanceState != null) {
                mMaxLookback = savedInstanceState.getInt(BUNDLE_MAX_LOOKBACK);
                mSyncEnabledPref
                        .setChecked(savedInstanceState.getBoolean(BUNDLE_SYNC_ENABLED_VALUE));
                mSyncLookbackPref.setValue(savedInstanceState.getString(BUNDLE_SYNC_WINDOW_VALUE));
                onDataLoaded();
            } else {
                // Make them disabled until we load data
                enablePreferences(false);
                getLoaderManager().initLoader(0, getArguments(),
                        new MailboxMaxLookbackLoaderCallbacks());
            }
        }

        private void enablePreferences(boolean enabled) {
            mSyncEnabledPref.setEnabled(enabled);
            mSyncLookbackPref.setEnabled(enabled);
        }

        @Override
        public void onSaveInstanceState(@NonNull Bundle outState) {
            super.onSaveInstanceState(outState);
            outState.putInt(BUNDLE_MAX_LOOKBACK, mMaxLookback);
            outState.putBoolean(BUNDLE_SYNC_ENABLED_VALUE, mSyncEnabledPref.isChecked());
            outState.putString(BUNDLE_SYNC_WINDOW_VALUE, mSyncLookbackPref.getValue());
        }

        /**
         * We save all the settings in onDestroy, *unless it's for configuration changes*.
         */
        @Override
        public void onDestroy() {
            super.onDestroy();
            if (!getActivity().isChangingConfigurations()) {
                saveToDatabase();
            }
        }

        private static class MailboxMaxLookbackLoader extends MailAsyncTaskLoader<Integer> {
            /** Projection for loading an account's policy key. */
            private static final String[] POLICY_KEY_PROJECTION =
                    { AccountColumns.POLICY_KEY };
            private static final int POLICY_KEY_COLUMN = 0;

            /** Projection for loading the max email lookback. */
            private static final String[] MAX_EMAIL_LOOKBACK_PROJECTION =
                    { Policy.MAX_EMAIL_LOOKBACK };
            private static final int MAX_EMAIL_LOOKBACK_COLUMN = 0;

            private final long mAccountKey;

            private MailboxMaxLookbackLoader(Context context, long accountKey) {
                super(context);
                mAccountKey = accountKey;
            }

            @Override
            public Integer loadInBackground() {
                // Get the max lookback from our policy, if we have one.
                final Long policyKey = Utility.getFirstRowLong(getContext(),
                        ContentUris.withAppendedId(Account.CONTENT_URI, mAccountKey),
                        POLICY_KEY_PROJECTION, null, null, null, POLICY_KEY_COLUMN);
                if (policyKey == null) {
                    // No policy, nothing to look up.
                    return null;
                }

                return Utility.getFirstRowInt(getContext(),
                        ContentUris.withAppendedId(Policy.CONTENT_URI, policyKey),
                        MAX_EMAIL_LOOKBACK_PROJECTION, null, null, null,
                        MAX_EMAIL_LOOKBACK_COLUMN, 0);
            }

            @Override
            protected void onDiscardResult(Integer result) {}
        }

        private class MailboxMaxLookbackLoaderCallbacks
                implements LoaderManager.LoaderCallbacks<Integer> {
            @Override
            public Loader<Integer> onCreateLoader(int id, Bundle args) {
                final Mailbox mailbox = args.getParcelable(EXTRA_MAILBOX);
                return new MailboxMaxLookbackLoader(getActivity(), mailbox.mAccountKey);
            }

            @Override
            public void onLoadFinished(Loader<Integer> loader, Integer data) {
                mMaxLookback = data;

                mSyncEnabledPref.setChecked(mMailbox.mSyncInterval != 0);
                mSyncLookbackPref.setValue(String.valueOf(mMailbox.mSyncLookback));
                onDataLoaded();
                if (mMailbox.mType != Mailbox.TYPE_DRAFTS) {
                    enablePreferences(true);
                }
            }

            @Override
            public void onLoaderReset(Loader<Integer> loader) {}
        }

        /**
         * Called when {@link #mMailbox} is loaded (either by the loader or from the saved state).
         */
        private void onDataLoaded() {
            MailboxSettings.setupLookbackPreferenceOptions(getActivity(), mSyncLookbackPref,
                    mMaxLookback, true);
        }

        private final OnPreferenceChangeListener mPreferenceChanged =
                new OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                if (preference == mSyncEnabledPref) {
                    int newInterval = ((Boolean) newValue).booleanValue() ? 1 : 0;
                    mActivity.onMailboxSyncIntervalChanged(mMailbox, newInterval);
                    mValuesChanged = true;
                    return true;
                } else if (preference == mSyncLookbackPref) {
                    mSyncLookbackPref.setValue((String) newValue);
                    mSyncLookbackPref.setSummary(mSyncLookbackPref.getEntry());
                    int newLookback = Integer.valueOf((String) newValue);
                    mActivity.onMailboxSyncLookbackChanged(mMailbox, newLookback);
                    mValuesChanged = true;
                    return false;
                } else {
                    return true;
                }
            }
        };

        /**
         * Save changes to the database.
         *
         * Note it's called from {@link #onDestroy()}, which is called on the UI thread where we're
         * not allowed to touch the database, so it uses {@link EmailAsyncTask} to do the save on a
         * bg thread. This unfortunately means there's a chance that the app gets killed before the
         * save is finished.
         */
        private void saveToDatabase() {
            // Only save if a preference has changed value.
            if (!mValuesChanged) {
                return;
            }

            final int syncInterval = mSyncEnabledPref.isChecked() ? 1 : 0;
            final int syncLookback = Integer.valueOf(mSyncLookbackPref.getValue());

            LogUtils.i(Logging.LOG_TAG, "Saving mailbox settings...");
            enablePreferences(false);

            final long id = mMailbox.mId;
            final Context context = getActivity().getApplicationContext();

            new EmailAsyncTask<Void, Void, Void> (null /* no cancel */) {
                @Override
                protected Void doInBackground(Void... params) {
                    final ContentValues cv = new ContentValues(2);
                    final Uri uri;
                    cv.put(MailboxColumns.SYNC_INTERVAL, syncInterval);
                    cv.put(MailboxColumns.SYNC_LOOKBACK, syncLookback);
                    uri = ContentUris.withAppendedId(Mailbox.CONTENT_URI, id);
                    context.getContentResolver().update(uri, cv, null, null);

                    LogUtils.i(Logging.LOG_TAG, "Saved: " + uri);
                    return null;
                }
            }.executeSerial((Void [])null);
        }
    }
}
