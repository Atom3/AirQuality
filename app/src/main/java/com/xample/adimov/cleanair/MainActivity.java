package com.xample.adimov.cleanair;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * MainActivity — loads user-added sensors only, updates DB, shows user coords in ActionBar and locationTextView.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String SENSOR_API_URL = "https://data.sensor.community/static/v2/data.json";
    private static final int LOCATION_REQUEST_CODE = 100;

    private final List<CityData> cityList = new ArrayList<>();
    private Adapter mAdapter;
    private SensorDatabaseHelper dbHelper;
    private LocationManager locationManager;
    private TextView locationTextView; // optional UI element in layout

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        FloatingActionButton addCity = findViewById(R.id.NearestStation);
        RecyclerView recyclerView = findViewById(R.id.list1);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));

        // optional TextView to show coords if present in your layout (id: locationTextView)
        locationTextView = findViewById(getResources().getIdentifier("locationTextView", "id", getPackageName()));

        dbHelper = new SensorDatabaseHelper(this);

        mAdapter = new Adapter(this, cityList);
        recyclerView.setAdapter(mAdapter);

        // load **only user-added** sensors
        loadSensorsFromDatabase();

        // update DB in background (if needed)
        checkAndUpdateDatabase();

        // keep original behaviour: fetch base sensor once (not user-added)
        fetchSensorData(11522, "Varna base", false);

        addCity.setOnClickListener(v -> addNearestSensor());
    }

    /**
     * Loads only user-added sensors (is_user_added = 1).
     */
    private void loadSensorsFromDatabase() {
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

                    cityList.add(new CityData(id, cityName, pm10, pm25, lat, lon));
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "loadSensorsFromDatabase error: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
            if (mAdapter != null) mAdapter.notifyDataSetChanged();
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

    /**
     * Downloads the big sensor list and stores as cached (isUserAdded = false).
     */
    private void updateSensorDatabase() {
        new Thread(() -> {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build();

            Request request = new Request.Builder().url(SENSOR_API_URL).build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    Log.e(TAG, "Failed to fetch sensor data. code=" + (response != null ? response.code() : "n/a"));
                    return;
                }
                String responseData = response.body().string();
                storeSensorLocations(responseData); // those will be inserted with isUserAdded = false
            } catch (IOException e) {
                Log.e(TAG, "Database Update Failed: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Parse big dataset and store sensors as cached (isUserAdded = false).
     */
    private void storeSensorLocations(String jsonData) {
        try {
            JSONArray sensorsArray = new JSONArray(jsonData);
            int stored = 0;
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
                        double d;
                        try { d = Double.parseDouble(val); } catch (Exception ex) { d = 0; }
                        if ("P1".equalsIgnoreCase(t)) pm10 = d;
                        if ("P2".equalsIgnoreCase(t)) pm25 = d;
                    }
                }

                // store as cached (not user-added)
                dbHelper.insertOrUpdateSensor(sensorId, "Sensor " + sensorId, lat, lon, pm10, pm25, false);
                stored++;
            }
            Log.d(TAG, "Stored sensors (cached): " + stored);
        } catch (JSONException e) {
            Log.e(TAG, "storeSensorLocations JSON error: " + e.getMessage());
        }
    }

    private double tryParseDouble(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return 0.0; }
    }

    /**
     * Add nearest sensor — this is a user action so we fetch sensor and persist as user-added.
     * Also updates ActionBar title and optional locationTextView with rounded coordinates.
     */
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
        if (getSupportActionBar() != null) getSupportActionBar().setTitle(title);
        if (locationTextView != null) locationTextView.setText(title); // optional UI element

        int nearest = findNearestSensor(location.getLatitude(), location.getLongitude());
        if (nearest != -1) {
            // fetch and persist as user-added
            fetchSensorData(nearest, "Nearest Sensor", true);
        } else {
            Toast.makeText(this, "No nearby sensors available!", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Fetch sensor data - the isUserAdded flag controls whether parse/persist marks row as user-added.
     */
    private void fetchSensorData(int sensorId, String cityName, boolean isUserAdded) {
        new Thread(() -> {
            OkHttpClient client = new OkHttpClient();
            Request request = new Request.Builder().url("https://data.sensor.community/airrohr/v1/sensor/" + sensorId + "/").build();
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String responseData = response.body().string();
                    parseSensorData(responseData, cityName, sensorId, isUserAdded);
                } else {
                    Log.e(TAG, "API Request Failed for sensor " + sensorId);
                }
            } catch (IOException e) {
                Log.e(TAG, "Network Error: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Find nearest sensor id using cached sensors table (cached entries are present regardless of is_user_added).
     */
    private int findNearestSensor(double userLat, double userLon) {
        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            db = dbHelper.getReadableDatabase();
            cursor = db.rawQuery("SELECT id, latitude, longitude FROM sensors", null);
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
            Log.e(TAG, "findNearestSensor error: " + e.getMessage());
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

    /**
     * Parse sensor data JSON and persist; uses isUserAdded flag passed from fetchSensorData.
     */
    private void parseSensorData(String jsonData, String cityName, int sensorId, boolean isUserAdded) {
        try {
            JSONArray arr = new JSONArray(jsonData);
            if (arr.length() == 0) return;

            String pm10 = "N/A", pm25 = "N/A";
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

            // attempt to get latitude/longitude from cached DB row (if any)
            double lat = 0, lon = 0;
            SQLiteDatabase db = null;
            Cursor cursor = null;
            try {
                db = dbHelper.getReadableDatabase();
                cursor = db.rawQuery("SELECT latitude, longitude FROM sensors WHERE id = ?", new String[]{String.valueOf(sensorId)});
                if (cursor != null && cursor.moveToFirst()) {
                    lat = cursor.isNull(0) ? 0 : cursor.getDouble(0);
                    lon = cursor.isNull(1) ? 0 : cursor.getDouble(1);
                }
            } catch (Exception e) {
                Log.d(TAG, "No coords in DB for sensor " + sensorId);
            } finally {
                if (cursor != null) cursor.close();
                if (db != null) db.close();
            }

            double pm10d = tryParseDouble(pm10);
            double pm25d = tryParseDouble(pm25);

            // Persist with the flag the caller requested
            dbHelper.insertOrUpdateSensor(sensorId, cityName, lat, lon, pm10d, pm25d, isUserAdded);

            final String finalPm10 = pm10;
            final String finalPm25 = pm25;
            final double finalLat = lat;
            final double finalLon = lon;

            runOnUiThread(() -> {
                // if this was user-added, show it in UI (on top); if not user-added, do not automatically add to the user's list
                if (isUserAdded) {
                    cityList.add(0, new CityData(sensorId, cityName, finalPm10, finalPm25, finalLat, finalLon));
                    if (mAdapter != null) mAdapter.notifyDataSetChanged();
                } else {
                    // if the sensor is already present in cityList (unlikely), update values there
                    for (int i = 0; i < cityList.size(); i++) {
                        CityData cd = cityList.get(i);
                        if (cd.getSensorId() == sensorId) {
                            cd.setPM10(finalPm10);
                            cd.setPM25(finalPm25);
                            if (mAdapter != null) mAdapter.notifyItemChanged(i);
                            return;
                        }
                    }
                }
            });

        } catch (JSONException e) {
            Log.e(TAG, "parseSensorData JSON error: " + e.getMessage());
        }
    }
}
