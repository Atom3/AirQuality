package com.xample.adimov.cleanair;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;

import androidx.recyclerview.widget.RecyclerView;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * RecyclerView Adapter for CityData rows.
 * Provides per-row refresh and long-press rename/delete.
 */
public class Adapter extends RecyclerView.Adapter<CityViewHolder> {

    private static final String TAG = "Adapter";
    private final List<CityData> cityDataList;
    private final Context mContext;
    private final OkHttpClient client = new OkHttpClient();

    public Adapter(Context mContext, List<CityData> cityData) {
        this.cityDataList = cityData;
        this.mContext = mContext;
    }

    @Override
    public CityViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.citybox, parent, false);
        return new CityViewHolder(view);
    }

    @Override
    public void onBindViewHolder(final CityViewHolder holder, int position) {
        CityData city = cityDataList.get(position);

        String name = safeGetName(city);
        String pm10 = safeGetPM10(city);
        String pm25 = safeGetPM25(city);

        holder.City.setText(name);
        holder.PM10.setText(pm10);
        holder.PM25.setText(pm25);

        float pm10f = parseFloat(pm10);
        float pm25f = parseFloat(pm25);
        float maxValue = Math.max(pm10f, pm25f);

        if (maxValue < 15) holder.itemView.setBackgroundColor(Color.GREEN);
        else if (maxValue < 75) holder.itemView.setBackgroundColor(Color.YELLOW);
        else holder.itemView.setBackgroundColor(Color.RED);

        // Refresh listener
        holder.refreshButton.setOnClickListener(v -> {
            holder.refreshButton.setEnabled(false);
            refreshSensorData(holder, city);
            new Handler(Looper.getMainLooper()).postDelayed(() -> holder.refreshButton.setEnabled(true), 10000);
        });

        // Long-press -> Rename / Delete
        holder.itemView.setOnLongClickListener(v -> {
            CharSequence[] options = {"Rename", "Delete"};
            new AlertDialog.Builder(mContext)
                    .setTitle("Manage sensor")
                    .setItems(options, (dialog, which) -> {
                        if (which == 0) {
                            // Rename
                            EditText input = new EditText(mContext);
                            input.setText(name);
                            new AlertDialog.Builder(mContext)
                                    .setTitle("Rename sensor")
                                    .setView(input)
                                    .setPositiveButton("Save", (d, w) -> {
                                        String newName = input.getText().toString().trim();
                                        if (!newName.isEmpty()) {
                                            // update DB
                                            int id = city.getSensorId();
                                            SensorDatabaseHelper helper = new SensorDatabaseHelper(mContext);
                                            helper.updateSensorNameById(id, newName);
                                            // update memory and UI
                                            city.setName(newName);
                                            notifyItemChanged(holder.getAdapterPosition());
                                            Toast.makeText(mContext, "Renamed", Toast.LENGTH_SHORT).show();
                                        }
                                    })
                                    .setNegativeButton("Cancel", null)
                                    .show();
                        } else {
                            // Delete
                            int id = city.getSensorId();
                            SensorDatabaseHelper helper = new SensorDatabaseHelper(mContext);
                            helper.deleteSensorById(id);
                            int pos = holder.getAdapterPosition();
                            if (pos >= 0 && pos < cityDataList.size()) {
                                cityDataList.remove(pos);
                                notifyItemRemoved(pos);
                                Toast.makeText(mContext, "Deleted", Toast.LENGTH_SHORT).show();
                            }
                        }
                    })
                    .show();
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return cityDataList.size();
    }

    private float parseFloat(String value) {
        try { return Float.parseFloat(value); } catch (Exception e) { return 0; }
    }

    private String safeGetName(CityData c) {
        try { return c.getName(); } catch (Exception e) { return "Sensor"; }
    }

    private String safeGetPM10(CityData c) {
        try { return c.getPM10(); } catch (Exception e) { return "N/A"; }
    }

    private String safeGetPM25(CityData c) {
        try { return c.getPM25(); } catch (Exception e) { return "N/A"; }
    }

    private void refreshSensorData(CityViewHolder holder, CityData city) {
        int sensorId = city.getSensorId();
        if (sensorId < 0) return;

        String API_URL = "https://data.sensor.community/airrohr/v1/sensor/" + sensorId + "/";

        new Thread(() -> {
            Request request = new Request.Builder().url(API_URL).build();
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String responseData = response.body().string();
                    Log.d(TAG, "API Response for " + sensorId + ": " + responseData);
                    updateSensorValues(responseData, holder, city);
                } else {
                    Log.e(TAG, "Refresh API failed for " + sensorId);
                }
            } catch (IOException e) {
                Log.e(TAG, "Network Error: " + e.getMessage());
            }
        }).start();
    }

    private void updateSensorValues(String jsonData, CityViewHolder holder, CityData city) {
        try {
            JSONArray jsonArray = new JSONArray(jsonData);
            if (jsonArray.length() == 0) return;

            String pm10Value = "N/A", pm25Value = "N/A";

            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject sensorEntry = jsonArray.getJSONObject(i);
                JSONArray values = sensorEntry.optJSONArray("sensordatavalues");
                if (values == null) continue;
                for (int j = 0; j < values.length(); j++) {
                    JSONObject valueObj = values.getJSONObject(j);
                    String type = valueObj.optString("value_type", "");
                    String val = valueObj.optString("value", "");
                    if ("P1".equalsIgnoreCase(type)) pm10Value = val;
                    if ("P2".equalsIgnoreCase(type)) pm25Value = val;
                }
            }

            final String finalPm10 = pm10Value;
            final String finalPm25 = pm25Value;

            new Handler(Looper.getMainLooper()).post(() -> {
                city.setPM10(finalPm10);
                city.setPM25(finalPm25);
                holder.PM10.setText(finalPm10);
                holder.PM25.setText(finalPm25);

                float pm10f = parseFloat(finalPm10);
                float pm25f = parseFloat(finalPm25);
                float maxValue = Math.max(pm10f, pm25f);
                if (maxValue < 15) holder.itemView.setBackgroundColor(Color.GREEN);
                else if (maxValue < 75) holder.itemView.setBackgroundColor(Color.YELLOW);
                else holder.itemView.setBackgroundColor(Color.RED);

                Toast.makeText(mContext, "Values updated", Toast.LENGTH_SHORT).show();
            });

        } catch (JSONException e) {
            Log.e(TAG, "JSON Parsing Error: " + e.getMessage());
        }
    }
}

// ViewHolder class
class CityViewHolder extends RecyclerView.ViewHolder {
    TextView City;
    TextView PM10;
    TextView PM25;
    Button refreshButton;

    public CityViewHolder(View itemView) {
        super(itemView);
        City = itemView.findViewById(R.id.city);
        PM10 = itemView.findViewById(R.id.pm10value);
        PM25 = itemView.findViewById(R.id.pm25value);
        refreshButton = itemView.findViewById(R.id.refreshButton);
    }
}
