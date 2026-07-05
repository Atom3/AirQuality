package com.xample.adimov.cleanair;

import android.Manifest;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.core.view.WindowCompat;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import android.util.Log;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String SENSOR_API_URL = "https://data.sensor.community/static/v2/data.dust.min.json";
    private static final int LOCATION_REQUEST_CODE = 100;

    private final List<CityData> cityList = new ArrayList<>();
    private Adapter mAdapter;
    private SensorDatabaseHelper dbHelper;
    private LocationManager locationManager;
    private TextView locationTextView;
    private SwipeRefreshLayout swipeRefreshLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. Status Bar Fix: Make the icons dark so they are visible on a white background!
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(true);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // 2. Distance Fix: Quietly grab the user's last known location on startup to seed the distance math
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc != null) {
                SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
                prefs.edit()
                        .putFloat("user_lat", (float) loc.getLatitude())
                        .putFloat("user_lon", (float) loc.getLongitude())
                        .apply();
            }
        }

        ImageButton helpButton = findViewById(R.id.help_button);
        helpButton.setOnClickListener(v -> {
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("UI tips")
                    .setMessage("Long press on any sensor in the list to rename or delete it.\n\nTap a sensor to view time of data, distance and its location.\n\nYou can copy coordinates directly in google maps to find exact sensor locations.\n\nPM2.5 show the number of particles ≤ 2.5 micrometers per cubic meter. PM10 is the same but for particles ≤ 10. A value under 25 is considered good and will be shown in green.")
                    .setPositiveButton("Got it!", null)
                    .show();
        });

        FloatingActionButton addCity = findViewById(R.id.NearestStation);
        RecyclerView recyclerView = findViewById(R.id.list1);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));

        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        swipeRefreshLayout.setOnRefreshListener(this::refreshAllSensors);

        locationTextView = findViewById(R.id.locationTextView);
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        String savedLocation = prefs.getString("last_location", "Tap the target button to find your nearest sensor");
        locationTextView.setText(savedLocation);

        dbHelper = new SensorDatabaseHelper(this);
        mAdapter = new Adapter(this, cityList);
        recyclerView.setAdapter(mAdapter);

        loadSensorsFromDatabase();
        checkAndUpdateDatabase();

        addCity.setOnClickListener(v -> addNearestSensor());
    }

    private void refreshAllSensors() {
        if (cityList.isEmpty()) {
            swipeRefreshLayout.setRefreshing(false);
            return;
        }

        for (CityData city : cityList) {
            fetchSensorData(city.getSensorId(), city.getName(), false);
        }

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (swipeRefreshLayout.isRefreshing()) swipeRefreshLayout.setRefreshing(false);
            Toast.makeText(MainActivity.this, "Sensors updated!", Toast.LENGTH_SHORT).show();
        }, 2000);
    }

    private void loadSensorsFromDatabase() {
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        float userLat = prefs.getFloat("user_lat", 0f);
        float userLon = prefs.getFloat("user_lon", 0f);

        Cursor cursor = null;
        try {
            cursor = dbHelper.getUserAddedSensors();
            cityList.clear();
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    int id = cursor.getInt(0);
                    String cityName = cursor.isNull(1) ? ("Sensor " + id) : cursor.getString(1);
                    double lat = cursor.isNull(2) ? 0.0 : cursor.getDouble(2);
                    double lon = cursor.isNull(3) ? 0.0 : cursor.getDouble(3);
                    String pm10 = cursor.isNull(4) ? "N/A" : String.valueOf(cursor.getDouble(4));
                    String pm25 = cursor.isNull(5) ? "N/A" : String.valueOf(cursor.getDouble(5));

                    CityData city = new CityData(id, cityName, pm10, pm25, lat, lon);

                    // Calculate distance for UI persistence on startup
                    if (userLat != 0f && userLon != 0f) {
                        double distKm = haversine(userLat, userLon, lat, lon);
                        if (distKm < 1.0) city.setDistance(Math.round(distKm * 1000) + " m");
                        else city.setDistance(Math.round(distKm) + " km");
                    }

                    cityList.add(city);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "loadSensorsFromDatabase error: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
            if (mAdapter != null) mAdapter.notifyDataSetChanged();

            // Automatically pull fresh data in background so times update
            refreshAllSensors();
        }
    }

    private void checkAndUpdateDatabase() {
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        long lastUpdate = prefs.getLong("last_update", 0);
        long currentTime = System.currentTimeMillis();
        long twoDays = 2L * 24 * 60 * 60 * 1000;

        if (currentTime - lastUpdate > twoDays) {
            updateSensorDatabase();
            prefs.edit().putLong("last_update", currentTime).apply();
        }
    }

    private void updateSensorDatabase() {
        new Thread(() -> {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build();

            Request request = new Request.Builder().url(SENSOR_API_URL).build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return;
                storeSensorLocations(response.body().string());
            } catch (IOException e) {
                Log.e(TAG, "Database Update Failed: " + e.getMessage());
            }
        }).start();
    }

    private void storeSensorLocations(String jsonData) {
        try {
            JSONArray sensorsArray = new JSONArray(jsonData);
            for (int i = 0; i < sensorsArray.length(); i++) {
                JSONObject sensorObj = sensorsArray.getJSONObject(i);
                JSONObject sensor = sensorObj.optJSONObject("sensor");
                JSONObject location = sensorObj.optJSONObject("location");
                if (sensor == null || location == null) continue;

                int sensorId = sensor.optInt("id", -1);
                double lat = tryParseDouble(location.optString("latitude", "0"));
                double lon = tryParseDouble(location.optString("longitude", "0"));

                double pm10 = 0, pm25 = 0;
                JSONArray vals = sensorObj.optJSONArray("sensordatavalues");
                if (vals != null) {
                    for (int j = 0; j < vals.length(); j++) {
                        JSONObject v = vals.getJSONObject(j);
                        String t = v.optString("value_type", "");
                        String val = v.optString("value", "0");
                        if ("P1".equalsIgnoreCase(t)) pm10 = tryParseDouble(val);
                        if ("P2".equalsIgnoreCase(t)) pm25 = tryParseDouble(val);
                    }
                }
                dbHelper.insertOrUpdateSensor(sensorId, "Sensor " + sensorId, lat, lon, pm10, pm25, false);
            }
        } catch (JSONException e) {
            Log.e(TAG, "storeSensorLocations JSON error: " + e.getMessage());
        }
    }

    private double tryParseDouble(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return 0.0; }
    }

    private void addNearestSensor() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_REQUEST_CODE);
            return;
        }

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        Location location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);

        if (location == null) {
            Toast.makeText(this, "Could not get location!", Toast.LENGTH_SHORT).show();
            return;
        }

        double latRounded = Math.round(location.getLatitude() * 1000.0) / 1000.0;
        double lonRounded = Math.round(location.getLongitude() * 1000.0) / 1000.0;

        String title = String.format("Lat: %.3f, Lon: %.3f", latRounded, lonRounded);

        // Save Location to Memory for distance math
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        prefs.edit()
                .putString("last_location", title)
                .putFloat("user_lat", (float) location.getLatitude())
                .putFloat("user_lon", (float) location.getLongitude())
                .apply();

        locationTextView.setText(title);

        int nearest = findNearestSensor(location.getLatitude(), location.getLongitude());
        if (nearest != -1) {
            fetchSensorData(nearest, "Nearest Sensor", true);
        } else {
            Toast.makeText(this, "No nearby sensors available!", Toast.LENGTH_SHORT).show();
        }
    }

    private void fetchSensorData(int sensorId, String cityName, boolean isUserAction) {
        new Thread(() -> {
            OkHttpClient client = new OkHttpClient();
            Request request = new Request.Builder().url("https://data.sensor.community/airrohr/v1/sensor/" + sensorId + "/").build();
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    parseSensorData(response.body().string(), cityName, sensorId, isUserAction);
                }
            } catch (IOException e) {
                Log.e(TAG, "Network Error: " + e.getMessage());
            }
        }).start();
    }

    private void parseSensorData(String jsonData, String cityName, int sensorId, boolean isUserAction) {
        try {
            JSONArray arr = new JSONArray(jsonData);

            String pm10 = "N/A";
            String pm25 = "N/A";
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
                        if ("P1".equalsIgnoreCase(t)) pm10 = val;
                        if ("P2".equalsIgnoreCase(t)) pm25 = val;
                    }
                }
            } else if (!isUserAction) {
                return;
            }

            // Retrieve coordinates from DB
            double lat = 0, lon = 0;
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            Cursor cursor = db.rawQuery("SELECT latitude, longitude FROM sensors WHERE id = ?", new String[]{String.valueOf(sensorId)});
            if (cursor != null && cursor.moveToFirst()) {
                lat = cursor.isNull(0) ? 0 : cursor.getDouble(0);
                lon = cursor.isNull(1) ? 0 : cursor.getDouble(1);
                cursor.close();
            }
            db.close();

            // Calculate Distance
            SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
            float userLat = prefs.getFloat("user_lat", 0f);
            float userLon = prefs.getFloat("user_lon", 0f);
            String distanceStr = "Unknown";

            if (userLat != 0f && userLon != 0f && lat != 0.0 && lon != 0.0) {
                double distKm = haversine(userLat, userLon, lat, lon);
                if (distKm < 1.0) distanceStr = Math.round(distKm * 1000) + " m";
                else distanceStr = Math.round(distKm) + " km";
            }

            final String finalPm10 = pm10;
            final String finalPm25 = pm25;
            final double finalLat = lat;
            final double finalLon = lon;
            final double pm10d = tryParseDouble(pm10);
            final double pm25d = tryParseDouble(pm25);
            final String finalTime = formattedTime;
            final String finalDistance = distanceStr;

            runOnUiThread(() -> {
                boolean existsInList = false;

                for (int i = 0; i < cityList.size(); i++) {
                    if (cityList.get(i).getSensorId() == sensorId) {
                        cityList.get(i).setPM10(finalPm10);
                        cityList.get(i).setPM25(finalPm25);
                        cityList.get(i).setTime(finalTime);
                        cityList.get(i).setDistance(finalDistance);
                        if (mAdapter != null) mAdapter.notifyItemChanged(i);
                        existsInList = true;
                        break;
                    }
                }

                if (isUserAction && !existsInList) {
                    CityData newCity = new CityData(sensorId, cityName, finalPm10, finalPm25, finalLat, finalLon);
                    newCity.setTime(finalTime);
                    newCity.setDistance(finalDistance);
                    cityList.add(0, newCity);
                    if (mAdapter != null) mAdapter.notifyItemInserted(0);
                    existsInList = true;
                }

                final boolean finalIsUserAdded = existsInList;
                new Thread(() -> {
                    dbHelper.insertOrUpdateSensor(sensorId, cityName, finalLat, finalLon, pm10d, pm25d, finalIsUserAdded);
                }).start();
            });

        } catch (JSONException e) {
            Log.e(TAG, "parseSensorData JSON error: " + e.getMessage());
        }
    }

    private int findNearestSensor(double userLat, double userLon) {
        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            db = dbHelper.getReadableDatabase();
            cursor = db.rawQuery("SELECT id, latitude, longitude FROM sensors WHERE pm10 > 0 OR pm25 > 0", null);
            if (cursor == null || cursor.getCount() == 0) return -1;

            int nearest = -1;
            double minDist = Double.MAX_VALUE;
            while (cursor.moveToNext()) {
                int id = cursor.getInt(0);
                double lat = cursor.isNull(1) ? 0 : cursor.getDouble(1);
                double lon = cursor.isNull(2) ? 0 : cursor.getDouble(2);
                double d = haversine(userLat, userLon, lat, lon);
                if (d < minDist) { minDist = d; nearest = id; }
            }
            return nearest;
        } catch (Exception e) {
            return -1;
        } finally {
            if (cursor != null) cursor.close();
            if (db != null) db.close();
        }
    }

    private double haversine(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2)*Math.sin(dLat/2) +
                Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon/2)*Math.sin(dLon/2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
        return R * c;
    }
}