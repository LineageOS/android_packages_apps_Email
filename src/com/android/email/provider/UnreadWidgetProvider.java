package com.android.email.provider;

import com.android.email.R;
import com.android.email.activity.Welcome;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.MailboxColumns;


import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.os.IBinder;
import android.view.View;
import android.widget.RemoteViews;

public class UnreadWidgetProvider extends AppWidgetProvider {

    public static String PREF_PREFIX = "email_unread_widget_";
    public static String UNREAD_WIDGET_UPDATE = "unread_widget_update";
    static final ComponentName UNREAD_PROVIDER = new ComponentName("com.android.email",
                "com.android.email.provider.UnreadWidgetProvider");

    public static AppWidgetManager mAppWidgetManager;
    public static int unreadCount = 0;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(intent.getAction())) {
            this.updateWidgets(context);
        }
        super.onReceive(context, intent);
    }


    public void updateWidgets(Context context) {
        final AppWidgetManager am = AppWidgetManager.getInstance(context);
        int[] ids = am.getAppWidgetIds(UNREAD_PROVIDER);
            context.startService(new Intent(context, UpdateWidgetService.class).
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, ids));
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        context.startService(new Intent(context,
                UpdateWidgetService.class).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS,
                appWidgetIds));
        super.onUpdate(context, appWidgetManager, appWidgetIds);
    }

    public static class UpdateWidgetService extends Service {

        private static final String[] MAILBOX_SUM_OF_UNREAD_COUNT_PROJECTION = new String [] {
                "sum(" + MailboxColumns.UNREAD_COUNT + ")"};
        private static final String ALL_UNREAD_COUNT_FOLDERS = MailboxColumns.TYPE +
                " IN (" + Mailbox.TYPE_INBOX + "," + Mailbox.TYPE_MAIL + ")";

        @Override
        public void onStart(Intent intent, int serviceId) {
            int[] ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_ID);
            if (ids != null) {
                for (int id : ids) {
                    RemoteViews updateViews = buildUpdate(this);
                    AppWidgetManager.getInstance(getApplication()).
                            updateAppWidget(id, updateViews);
                }
            }
            unreadCount = 0;
            stopSelf(serviceId);
        }

        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }

        public RemoteViews buildUpdate(Context context) {
            int count = 0;
            if (unreadCount == 0) {   // if this is first time thru
                Cursor c = context.getContentResolver().query(Mailbox.CONTENT_URI,
                        MAILBOX_SUM_OF_UNREAD_COUNT_PROJECTION,
                        ALL_UNREAD_COUNT_FOLDERS, null, null);
                try {
                    c.moveToPosition(-1);
                    while (c.moveToNext()) {
                        count += c.getInt(0);
                    }
                } finally {
                    c.close();
                }
                unreadCount = count; // keep from having to run query more than once per update cycle
            }
            Intent intent = new Intent(context, Welcome.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, 0);
            RemoteViews views = new RemoteViews(context.getPackageName(),
                    R.layout.unread_widget);
            if (unreadCount > 0) {
                views.setTextViewText(R.id.unread_count, Integer.toString(unreadCount));
                views.setTextColor(R.id.unread_count, Color.BLACK);
                views.setViewVisibility(R.id.unread_count, View.VISIBLE);
            } else {
                views.setViewVisibility(R.id.unread_count, View.GONE);
            }
            views.setOnClickPendingIntent(R.id.widget_layout, pendingIntent);
            return views;
        }
    }
}