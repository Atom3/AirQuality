package com.xample.adimov.cleanair;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class SensorDatabaseHelper extends SQLiteOpenHelper {
    private static final String TAG = "SensorDatabaseHelper";
    private static final String DATABASE_NAME = "sensors.db";
    private static final int DATABASE_VERSION = 3;

    public SensorDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sensors (" +
                "id INTEGER PRIMARY KEY, " +
                "city TEXT, " +
                "latitude REAL, " +
                "longitude REAL, " +
                "pm10 REAL, " +
                "pm25 REAL, " +
                "is_user_added INTEGER DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS sensors");
        onCreate(db);
    }

    /** Insert or update a sensor safely */
    public void insertOrUpdateSensor(int id, String city, double latitude, double longitude,
                                     double pm10, double pm25, boolean isUserAdded) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("city", city);
        values.put("latitude", latitude);
        values.put("longitude", longitude);
        values.put("pm10", pm10);
        values.put("pm25", pm25);
        values.put("is_user_added", isUserAdded ? 1 : 0);

        try {
            db.insertWithOnConflict("sensors", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            Log.d(TAG, "Inserted/Updated Sensor ID: " + id + " user=" + isUserAdded);
        } catch (Exception e) {
            Log.e(TAG, "DB Insert Error for Sensor ID " + id + ": " + e.getMessage());
        } finally {
            db.close();
        }
    }

    /** Get only sensors added by user */
    public Cursor getUserAddedSensors() {
        SQLiteDatabase db = this.getReadableDatabase();
        return db.rawQuery("SELECT * FROM sensors WHERE is_user_added = 1", null);
    }

    /** Get all sensors (for debugging or full list display) */
    public List<CityData> getAllSensors() {
        List<CityData> list = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT * FROM sensors", null);
        if (cursor.moveToFirst()) {
            do {
                int id = cursor.getInt(cursor.getColumnIndexOrThrow("id"));
                String city = cursor.getString(cursor.getColumnIndexOrThrow("city"));
                double lat = cursor.getDouble(cursor.getColumnIndexOrThrow("latitude"));
                double lon = cursor.getDouble(cursor.getColumnIndexOrThrow("longitude"));
                String pm10 = cursor.getString(cursor.getColumnIndexOrThrow("pm10"));
                String pm25 = cursor.getString(cursor.getColumnIndexOrThrow("pm25"));

                // Match CityData constructor order
                CityData c = new CityData(id, city, pm10, pm25, lat, lon);
                list.add(c);
            } while (cursor.moveToNext());
        }
        cursor.close();
        db.close();
        return list;
    }

    /** Update city name by ID */
    public void updateSensorNameById(int id, String newName) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("city", newName);
        db.update("sensors", values, "id=?", new String[]{String.valueOf(id)});
        db.close();
    }

    /** Delete a sensor by ID (Hides it from user view, but keeps in cache for distance math) */
    public void deleteSensorById(int id) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("is_user_added", 0); // 0 means false (hidden)

        db.update("sensors", values, "id=?", new String[]{String.valueOf(id)});
        db.close();
    }
    /** Update pm10 and pm25 values for a specific sensor during widget update for efficiency*/
    public void updateSensorDataValues(int id, double pm10, double pm25) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("pm10", pm10);
        values.put("pm25", pm25);

        // Update the row where the id matches
        int rowsAffected = db.update("sensors", values, "id=?", new String[]{String.valueOf(id)});

        if (rowsAffected > 0) {
            Log.d(TAG, "Successfully updated live values for Sensor ID: " + id);
        } else {
            Log.w(TAG, "Failed to update live values. Sensor ID not found: " + id);
        }

        db.close();
    }
}
