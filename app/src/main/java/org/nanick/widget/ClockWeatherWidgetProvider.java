package org.nanick.widget;

import android.Manifest;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.os.Build;
import android.util.Log;
import android.widget.RemoteViews;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;

public class ClockWeatherWidgetProvider extends AppWidgetProvider {
    private static final String TAG = "org.nanick.widget";
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final String API_KEY = "e1f10a1e78da46f5b10a1e78da96f525";
    private static double FALLBACK_LAT = 42.4935302;
    private static double FALLBACK_LON = -87.9003771;
    private static final String WEATHER_ENDPOINT = "https://api.weather.com/v3/wx/forecast/fifteenminute";
    private volatile Double Latitude = null;
    private volatile Double Longitude = null;
    private FusedLocationProviderClient fusedClient;
    private static final String GOOGLE_CLOCK_PKG = "com.google.android.deskclock";
    public String locationPin = new String(new byte[]{(byte) 239, (byte) 162, (byte) 153});

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);

        // Defensive: when the system asks for widget updates, ensure click remains attached
        if (AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(intent.getAction())) {
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            int[] ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS);
            if (ids == null) {
                ids = mgr.getAppWidgetIds(new ComponentName(context, ClockWeatherWidgetProvider.class));
            }
            if (ids != null && ids.length > 0) {
                onUpdate(context, mgr, ids);
            }
        }
    }


    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] appWidgetIds) {
        for (int widgetId : appWidgetIds) {
            // Update immediately (ensures click is present even before weather finishes)
            RemoteViews initial = new RemoteViews(context.getPackageName(), R.layout.widget_clock_weather);
            attachDeskClockClick(context, initial, widgetId);
            mgr.updateAppWidget(widgetId, initial);

            // Then fetch location + weather
            fetchLastKnownLocation(context, () -> fetchWeather(context, mgr, widgetId));
        }
    }

    private void initFusedClient(Context context) {
        if (fusedClient == null) {
            fusedClient = LocationServices.getFusedLocationProviderClient(context);
        }
    }
    private void fetchLastKnownLocation(Context context,Runnable onComplete) {
        initFusedClient(context);
        boolean coarseGranted = ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean fineGranted = ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!coarseGranted && !fineGranted) {
            this.Latitude = FALLBACK_LAT;
            this.Longitude = FALLBACK_LON;
            onComplete.run();
            return;
        }
        fusedClient.getLastLocation().addOnSuccessListener(location -> {
            if (location != null) {
                this.Latitude = location.getLatitude();
                this.Longitude = location.getLongitude();
                FALLBACK_LAT = this.Latitude;
                FALLBACK_LON = this.Longitude;
                Log.i("org.nanick.widget","Got coordinates from fusedClient");
                Log.i("org.nanick.widget",this.Latitude + "," + this.Longitude);
            } else {
                this.Latitude = FALLBACK_LAT;
                this.Longitude = FALLBACK_LON;
                Log.i("org.nanick.widget","Using FALLBACK_LAT and FALLBACK_LON");
            }

            onComplete.run();
        }).addOnFailureListener(e -> {
            this.Latitude = FALLBACK_LAT;
            this.Longitude = FALLBACK_LON;
            onComplete.run();
        });
    }

    private String buildWeatherUrl() {
        Double lat = 0.0D;
        Double lon = 0.0D;
        if(this.Latitude != null){
            lat = this.Latitude;
        } else {
            Log.i("org.nanick.widget","Using FALLBACK_LAT");
            lat = FALLBACK_LAT;
        }
        if(this.Longitude != null){
            lon = this.Longitude;
        } else {
            Log.i("org.nanick.widget","Using FALLBACK_LON");
            lon = FALLBACK_LON;
        }
        return WEATHER_ENDPOINT
                + "?geocode=" + lat.toString() + "%2C" + lon.toString()
                + "&units=e&language=en-US&format=json"
                + "&apiKey=" + API_KEY;

        // geocode=42.4935302%2C-87.9003771&units=e&language=en-US&format=json&apiKey=e1f10a1e78da46f5b10a1e78da96f525
    }

    private int getLocalIconResId(Context context, String iconCode) {
        if (iconCode == null){
            return 0;
        }
        String normalized = iconCode.replaceFirst("^0+(?!$)", "");
        String resName = "w_" + normalized.toLowerCase(Locale.US);
        return context.getResources().getIdentifier(resName,"drawable",context.getPackageName());
    }

    private void fetchWeather(Context context, AppWidgetManager mgr, int widgetId) {
        executor.execute(() -> {
            try {
                String city = null;
                String state = null;
                String county = null;
                String address = null;
                String zip = null;
                Geocoder geocoder = new Geocoder(context, Locale.getDefault());
                List<Address> addresses = geocoder.getFromLocation(this.Latitude, this.Longitude, 1);
                if (addresses != null && !addresses.isEmpty()) {
                    Address a = addresses.get(0);
                    city = a.getLocality();
                    state = a.getAdminArea();
                    county = a.getSubAdminArea();
                    address = a.getAddressLine(0);
                    Log.i("org.nanick.widget","{\n    \"city\": \"" + city + "\",\n    \"state\": \"" + state + "\",\n    \"county\": \"" + county + "\",\n    \"address\": \"" + address + "\"\n}");
                }
                String urlStr = buildWeatherUrl();
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("Accept", "*/*");
                InputStream is = conn.getInputStream();
                byte[] data = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    data = is.readAllBytes();
                }
                is.close();
                JSONObject json = new JSONObject(new String(data));
                int temp = json.getJSONArray("temperature").getInt(1);
//                int temp = json.getInt("temperature");
                String iconCode = json.getJSONArray("iconCode").getString(1);
//                String iconCode = json.getString("iconCode");
                int iconResId = getLocalIconResId(context, iconCode);
                RemoteViews views = new RemoteViews(context.getPackageName(),R.layout.widget_clock_weather);
                attachDeskClockClick(context, views, widgetId);
                views.setTextViewText(R.id.tempText, temp + "°F");
                if (iconResId != 0) {
                    views.setImageViewResource(R.id.weatherIcon, iconResId);
                } else {
                    views.setImageViewResource(R.id.weatherIcon, R.drawable.ic_weather_placeholder);
                }
                if (city != null) {
                    views.setTextViewText(R.id.locationText, "\uF899" + city);
                } else {
                    if(address != null){
                        views.setTextViewText(R.id.locationText, "\uF899" + address);
                    } else {
                        views.setTextViewText(R.id.locationText, "\uF899" + "Zion");
                    }
                }
                mgr.updateAppWidget(widgetId, views);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
    private void attachDeskClockClick(Context context, RemoteViews views, int widgetId) {
        PendingIntent pi = createGoogleClockPendingIntent(context, widgetId);
        if (pi != null) {
            views.setOnClickPendingIntent(R.id.cntr, pi);
        } else {
            Log.w(TAG, "Google Clock launch intent is null. Is com.google.android.deskclock installed?");
        }
    }

    private PendingIntent createGoogleClockPendingIntent(Context context, int widgetId) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(GOOGLE_CLOCK_PKG);

        // Some devices may not return a launcher intent for Google Clock even if present.
        // Try an explicit component fallback.
        if (launch == null) {
            launch = new Intent(Intent.ACTION_MAIN);
            launch.addCategory(Intent.CATEGORY_LAUNCHER);
            launch.setClassName(GOOGLE_CLOCK_PKG, "com.android.deskclock.DeskClock");
        }

        // If still not resolvable, return null (tap will do nothing).
        if (launch.resolveActivity(context.getPackageManager()) == null) {
            return null;
        }

        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        // IMPORTANT: unique requestCode per widgetId, otherwise PendingIntents can collide.
        return PendingIntent.getActivity(
                context,
                widgetId,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }
}
