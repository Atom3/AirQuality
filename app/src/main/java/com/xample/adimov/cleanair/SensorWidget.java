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
import android.util.Log;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class SensorWidget extends AppWidgetProvider {

    private static final String TAG = "SensorWidget";

    @Override
    public void onEnabled(Context context) {
        super.onEnabled(context);
        SharedPreferences prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        long interval = prefs.getLong("widget_refresh_interval", 60 * 60 * 1000L);
        scheduleUpdate(context, interval);
    }

    @Override
    public void onDisabled(Context context) {
        super.onDisabled(context);
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
            int[] ids = AppWidgetManager.getInstance(context).getAppWidgetIds(new ComponentName(context, SensorWidget.class));
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);

            PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            alarmManager.cancel(pendingIntent);
            alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + intervalMillis, intervalMillis, pendingIntent);
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
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

        // 2. Find the Nearest Sensor ID from Database
        SensorDatabaseHelper dbHelper = new SensorDatabaseHelper(context);
        Cursor cursor = null;

        int closestSensorId = -1;
        String sensorName = "Nearest Sensor"; // Default fallback
        double fallbackPm10 = 0.0;
        double fallbackPm25 = 0.0;

        SharedPreferences prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        float userLat = prefs.getFloat("user_lat", 0f);
        float userLon = prefs.getFloat("user_lon", 0f);
        double minDist = Double.MAX_VALUE;

        try {
            cursor = dbHelper.getUserAddedSensors();
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    double lat = cursor.isNull(2) ? 0.0 : cursor.getDouble(2);
                    double lon = cursor.isNull(3) ? 0.0 : cursor.getDouble(3);
                    double d = haversine(userLat, userLon, lat, lon);

                    // If user location is unknown, default to the first sensor in the list
                    if (d < minDist || (userLat == 0f && userLon == 0f)) {
                        minDist = d;
                        closestSensorId = cursor.getInt(0);
                        sensorName = cursor.isNull(1) ? "Sensor " + closestSensorId : cursor.getString(1);
                        fallbackPm10 = cursor.isNull(4) ? 0.0 : cursor.getDouble(4);
                        fallbackPm25 = cursor.isNull(5) ? 0.0 : cursor.getDouble(5);
                    }
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (cursor != null) cursor.close();
        }

        // If no sensors are in the database, do nothing
        if (closestSensorId == -1) {
            views.setTextViewText(R.id.widget_title, "No Sensors");
            appWidgetManager.updateAppWidget(appWidgetId, views);
            return;
        }

        // Show loading state while fetching from internet
        views.setTextViewText(R.id.widget_title, sensorName + "...");
        appWidgetManager.updateAppWidget(appWidgetId, views);

        // 3. Fetch Live Data for the Closest Sensor
        fetchLiveSensorData(context, appWidgetManager, appWidgetId, views, closestSensorId, sensorName, fallbackPm10, fallbackPm25);
    }

    private static void fetchLiveSensorData(Context context, AppWidgetManager appWidgetManager, int appWidgetId, RemoteViews views, int sensorId, String sensorName, double fallbackPm10, double fallbackPm25) {
        OkHttpClient client = new OkHttpClient();
        String url = "https://data.sensor.community/airrohr/v1/sensor/" + sensorId + "/";

        Request request = new Request.Builder().url(url).build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Widget network request failed. Using DB fallbacks.", e);
                // Update UI with old DB data on failure
                updateWidgetUI(context, appWidgetManager, appWidgetId, views, sensorName, fallbackPm10, fallbackPm25, "Offline");
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful() || response.body() == null) {
                    updateWidgetUI(context, appWidgetManager, appWidgetId, views, sensorName, fallbackPm10, fallbackPm25, "Offline");
                    return;
                }

                try {
                    String jsonData = response.body().string();
                    JSONArray arr = new JSONArray(jsonData);

                    double livePm10 = fallbackPm10;
                    double livePm25 = fallbackPm25;
                    String formattedTime = "N/A";

                    if (arr.length() > 0) {
                        JSONObject firstEntry = arr.getJSONObject(0);

                        // Parse Time
                        String timestamp = firstEntry.optString("timestamp", "");
                        if (!timestamp.isEmpty()) {
                            try {
                                SimpleDateFormat utcFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                                utcFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
                                Date date = utcFormat.parse(timestamp);

                                SimpleDateFormat localFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
                                localFormat.setTimeZone(TimeZone.getDefault());
                                formattedTime = localFormat.format(date);
                            } catch (Exception e) {
                                if (timestamp.length() >= 16) formattedTime = timestamp.substring(11, 16);
                            }
                        }

                        // Parse Values
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject entry = arr.getJSONObject(i);
                            JSONArray vals = entry.optJSONArray("sensordatavalues");
                            if (vals == null) continue;
                            for (int j = 0; j < vals.length(); j++) {
                                JSONObject v = vals.getJSONObject(j);
                                String t = v.optString("value_type", "");
                                String val = v.optString("value", "");
                                try {
                                    if ("P1".equalsIgnoreCase(t)) livePm10 = Double.parseDouble(val);
                                    if ("P2".equalsIgnoreCase(t)) livePm25 = Double.parseDouble(val);
                                } catch (Exception ignored) {}
                            }
                        }
                    }

                    // Update UI with live data
                    updateWidgetUI(context, appWidgetManager, appWidgetId, views, sensorName, livePm10, livePm25, formattedTime);

                    // Update local DB in background so main app gets it too
                    SensorDatabaseHelper dbHelper = new SensorDatabaseHelper(context);
                    dbHelper.updateSensorDataValues(sensorId, livePm10, livePm25);

                } catch (JSONException e) {
                    Log.e(TAG, "Widget JSON parsing failed", e);
                    updateWidgetUI(context, appWidgetManager, appWidgetId, views, sensorName, fallbackPm10, fallbackPm25, "Error");
                }
            }
        });
    }

    private static void updateWidgetUI(Context context, AppWidgetManager appWidgetManager, int appWidgetId, RemoteViews views, String sensorName, double pm10Val, double pm25Val, String timeStr) {
        String pm10Str = pm10Val == 0.0 ? "N/A" : String.format(Locale.getDefault(), "%.2f", pm10Val);
        String pm25Str = pm25Val == 0.0 ? "N/A" : String.format(Locale.getDefault(), "%.2f", pm25Val);

        views.setTextViewText(R.id.widget_title, sensorName);
        views.setTextViewText(R.id.widget_pm10, pm10Str);
        views.setTextViewText(R.id.widget_pm25, pm25Str);

        // 4. Determine Threshold Color (Updated to WHO guidelines)
        int backgroundDrawable = R.drawable.widget_background_green;
        if (pm25Val > 15 || pm10Val > 45) {
            backgroundDrawable = R.drawable.widget_background_yellow;
        }
        if (pm25Val > 35 || pm10Val > 75) {
            backgroundDrawable = R.drawable.widget_background_red;
        }
        views.setInt(R.id.widget_container, "setBackgroundResource", backgroundDrawable);

        // 5. Apply Timestamp
        views.setTextViewText(R.id.widget_time, timeStr.equals("Offline") || timeStr.equals("Error") ? "Updated: " + timeStr : "Updated: " + timeStr);

        // Instruct manager to refresh UI
        appWidgetManager.updateAppWidget(appWidgetId, views);
    }

    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371; // Earth radius in km
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}