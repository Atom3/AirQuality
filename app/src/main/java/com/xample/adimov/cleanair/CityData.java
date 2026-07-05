package com.xample.adimov.cleanair;

public class CityData {

    private int sensorId;
    private String name;
    private String pm10;
    private String pm25;
    private double latitude;
    private double longitude;

    public CityData(int sensorId, String name, String pm10, String pm25, double latitude, double longitude) {
        this.sensorId = sensorId;
        this.name = name;
        this.pm10 = pm10;
        this.pm25 = pm25;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    // --- Getters ---
    public int getSensorId() { return sensorId; }
    public String getName() { return name; }
    public String getPM10() { return pm10; }
    public String getPM25() { return pm25; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }

    // --- Setters ---
    public void setName(String name) { this.name = name; }
    public void setPM10(String pm10) { this.pm10 = pm10; }
    public void setPM25(String pm25) { this.pm25 = pm25; }
    public void setLatitude(double latitude) { this.latitude = latitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }
    private String time = "N/A";
    private String distance = "Unknown";

    public String getTime() { return time; }
    public void setTime(String time) { this.time = time; }

    public String getDistance() { return distance; }
    public void setDistance(String distance) { this.distance = distance; }

}
