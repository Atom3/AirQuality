package com.xample.adimov.cleanair;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SensorWidget extends AppWidgetProvider {

    @Override
    public void onEnabled(Context context) {
        super.onEnabled(context);
        // Start the alarm when the first widget is created
        SharedPreferences prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        long interval = prefs.getLong("widget_refresh_interval", 60 * 60 * 1000L);
        scheduleUpdate(context, interval);
    }

    @Override
    public void onDisabled(Context context) {
        super.onDisabled(context);
        // Cancel the alarm when the last widget is removed
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            Intent intent = new Intent(context, SensorWidget.class);
            intent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            alarmManager.cancel(pendingIntent);
        }
    }

    public static void scheduleUpdate(Context context, long intervalMillis) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            Intent intent = new Intent(context, SensorWidget.class);
            intent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);

            // We need to pass the widget IDs so onUpdate is triggered properly
            int[] ids = AppWidgetManager.getInstance(context).getAppWidgetIds(new ComponentName(context, SensorWidget.class));
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);

            PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            alarmManager.cancel(pendingIntent); // Cancel existing alarm
            alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + intervalMillis, intervalMillis, pendingIntent);
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {        for (int appWidgetId : appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId);
        }
    }

    static void updateAppWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_layout);

        // 1. Set click action: Tapping the widget opens the app
        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_container, pendingIntent);

        // 2. Fetch nearest user data from SQLite Database
        SensorDatabaseHelper dbHelper = new SensorDatabaseHelper(context);
        Cursor cursor = null;

        String pm10Str = "N/A";
        String pm25Str = "N/A";
        String sensorName = "Nearest Sensor"; // Default fallback
        double pm25Val = 0.0;
        double pm10Val = 0.0;

        try {
            cursor = dbHelper.getUserAddedSensors();
            // Grabbing the first (closest) user-tracked sensor in the table
            if (cursor != null && cursor.moveToFirst()) {
                // Column 1 is the 'city' name in your database
                sensorName = cursor.isNull(1) ? "Sensor " + cursor.getInt(0) : cursor.getString(1);

                pm10Val = cursor.isNull(4) ? 0.0 : cursor.getDouble(4);
                pm25Val = cursor.isNull(5) ? 0.0 : cursor.getDouble(5);

                // Format to exactly 2 decimal places instead of casting to (int)
                pm10Str = pm10Val == 0.0 ? "N/A" : String.format(Locale.getDefault(), "%.2f", pm10Val);
                pm25Str = pm25Val == 0.0 ? "N/A" : String.format(Locale.getDefault(), "%.2f", pm25Val);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (cursor != null) cursor.close();
        }

        // 3. Apply Texts
        views.setTextViewText(R.id.widget_title, sensorName);
        views.setTextViewText(R.id.widget_pm10, pm10Str);
        views.setTextViewText(R.id.widget_pm25, pm25Str);

        // 4. Determine Threshold Color
        int backgroundDrawable = R.drawable.widget_background_green;
        if (pm25Val > 25 || pm10Val > 50) {
            backgroundDrawable = R.drawable.widget_background_yellow;
        }
        if (pm25Val > 50 || pm10Val > 100) {
            backgroundDrawable = R.drawable.widget_background_red;
        }
        views.setInt(R.id.widget_container, "setBackgroundResource", backgroundDrawable);

        // 5. Apply Timestamp
        String currentTime = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        views.setTextViewText(R.id.widget_time, "Updated: " + currentTime);

        // Instruct manager to refresh UI
        appWidgetManager.updateAppWidget(appWidgetId, views);
    }
}