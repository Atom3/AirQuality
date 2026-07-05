package com.xample.adimov.cleanair;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * RecyclerView Adapter for CityData rows.
 * Provides expand/collapse for coordinates, copy button, and long-press rename/delete.
 */
public class Adapter extends RecyclerView.Adapter<Adapter.CityViewHolder> {

    private final List<CityData> cityDataList;
    private final Context mContext;

    public Adapter(Context mContext, List<CityData> cityData) {
        this.cityDataList = cityData;
        this.mContext = mContext;
    }

    @NonNull
    @Override
    public CityViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.citybox, parent, false);
        return new CityViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull final CityViewHolder holder, int position) {
        CityData city = cityDataList.get(position);

        String name = safeGetName(city);
        String pm10 = safeGetPM10(city);
        String pm25 = safeGetPM25(city);

        // Google Maps friendly format: "Latitude, Longitude"
        String coordinates = city.getLatitude() + ", " + city.getLongitude();

        holder.City.setText(name);
        holder.PM10.setText(pm10);
        holder.PM25.setText(pm25);
        holder.coordinatesText.setText("Loc: " + coordinates);
        String timeAndDist = city.getTime() + "  •  " + city.getDistance();
        holder.timeAndDistanceText.setText(timeAndDist);

        // Color coding logic
        float pm10f = parseFloat(pm10);
        float pm25f = parseFloat(pm25);
        float maxValue = Math.max(pm10f, pm25f);

        if (maxValue < 15) holder.mainRowLayout.setBackgroundColor(Color.GREEN);
        else if (maxValue < 75) holder.mainRowLayout.setBackgroundColor(Color.YELLOW);
        else holder.mainRowLayout.setBackgroundColor(Color.RED);

        // 1. Normal Click -> Expand / Collapse Coordinates
        holder.itemView.setOnClickListener(v -> {
            boolean isExpanded = holder.expandableDetailsLayout.getVisibility() == View.VISIBLE;
            holder.expandableDetailsLayout.setVisibility(isExpanded ? View.GONE : View.VISIBLE);
        });

        // 2. Copy Button Logic
        holder.copyButton.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) mContext.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Sensor Coordinates", coordinates);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(mContext, "Coordinates copied!", Toast.LENGTH_SHORT).show();
        });

        // 3. Long-press -> Rename / Delete
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
                                            int id = city.getSensorId();
                                            SensorDatabaseHelper helper = new SensorDatabaseHelper(mContext);
                                            helper.updateSensorNameById(id, newName);

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

    // ViewHolder class
    public static class CityViewHolder extends RecyclerView.ViewHolder {
        LinearLayout mainRowLayout;
        LinearLayout expandableDetailsLayout;
        TextView City;
        TextView PM10;
        TextView PM25;
        TextView coordinatesText;
        TextView timeAndDistanceText;
        Button copyButton;

        public CityViewHolder(View itemView) {
            super(itemView);
            mainRowLayout = itemView.findViewById(R.id.mainRowLayout);
            expandableDetailsLayout = itemView.findViewById(R.id.expandableDetailsLayout);
            City = itemView.findViewById(R.id.city);
            PM10 = itemView.findViewById(R.id.pm10value);
            PM25 = itemView.findViewById(R.id.pm25value);
            coordinatesText = itemView.findViewById(R.id.coordinatesText);
            copyButton = itemView.findViewById(R.id.copyButton);
            timeAndDistanceText = itemView.findViewById(R.id.timeAndDistanceText);
        }
    }
}