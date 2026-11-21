package com.hanatrial.tripjepang;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Layanan latar depan (notifikasi menetap) yang mengirim posisi ke Firestore lewat REST (:commit = upsert),
 * langsung dari sisi native, jadi tetap jalan walau layar terkunci dan halaman web dijeda.
 * Dokumen: keuangan_jepang/{trip}/lokasi/{id}, format sama dengan yang ditulis halaman web.
 */
public class LokasiService extends Service implements LocationListener {
    private static final String CH = "lokasi_trip";
    private LocationManager lm;
    private final ExecutorService ex = Executors.newSingleThreadExecutor();
    private String proyek = "", apiKey = "", trip = "", id = "", nama = "";
    private Location last;
    private long lastSend = 0;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            proyek = nz(intent.getStringExtra("proyek"));
            apiKey = nz(intent.getStringExtra("apiKey"));
            trip = nz(intent.getStringExtra("trip"));
            id = nz(intent.getStringExtra("id"));
            nama = nz(intent.getStringExtra("nama"));
        }
        tampilkanNotifikasi();
        mulaiLokasi();
        return START_STICKY;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private void tampilkanNotifikasi() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CH, "Lokasi trip", NotificationManager.IMPORTANCE_LOW));
        }
        Intent buka = new Intent(this, MainActivity.class);
        buka.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, buka, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setContentTitle("Lokasi trip aktif")
            .setContentText("Posisi " + nama + " dibagikan ke peserta. Matikan dari aplikasi.")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setOngoing(true);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(1, n);
        }
    }

    @SuppressWarnings("MissingPermission")
    private void mulaiLokasi() {
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) return;
        try { lm.removeUpdates(this); } catch (Exception ignored) { }
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10000L, 0f, this);
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 15000L, 0f, this);
            Location k = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (k == null) k = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (k != null && System.currentTimeMillis() - k.getTime() < 120000) kirimJikaPerlu(k, true);
        } catch (SecurityException ignored) { }
    }

    @Override
    public void onLocationChanged(Location l) { kirimJikaPerlu(l, false); }

    // Kirim kalau sudah bergerak >= 15 m, atau denyut tiap 60 detik saat diam.
    private void kirimJikaPerlu(Location l, boolean paksa) {
        long now = System.currentTimeMillis();
        boolean gerak = last == null || last.distanceTo(l) >= 15f;
        if (!paksa && !gerak && now - lastSend < 60000L) return;
        last = new Location(l);
        lastSend = now;
        final double lat = l.getLatitude(), lon = l.getLongitude();
        final int acc = Math.round(l.getAccuracy());
        ex.execute(() -> commit(true, lat, lon, acc));
    }

    private String namaDok() {
        return "projects/" + proyek + "/databases/(default)/documents/keuangan_jepang/" + trip + "/lokasi/" + id;
    }

    private void commit(boolean ubah, double lat, double lon, int acc) {
        if (proyek.isEmpty() || apiKey.isEmpty() || trip.isEmpty() || id.isEmpty()) return;
        HttpURLConnection c = null;
        try {
            JSONObject w = new JSONObject();
            if (ubah) {
                JSONObject f = new JSONObject();
                f.put("id", new JSONObject().put("integerValue", id));
                f.put("nama", new JSONObject().put("stringValue", nama));
                f.put("lat", new JSONObject().put("doubleValue", lat));
                f.put("lon", new JSONObject().put("doubleValue", lon));
                f.put("acc", new JSONObject().put("integerValue", String.valueOf(acc)));
                f.put("ts", new JSONObject().put("integerValue", String.valueOf(System.currentTimeMillis())));
                w.put("update", new JSONObject().put("name", namaDok()).put("fields", f));
            } else {
                w.put("delete", namaDok());
            }
            String body = new JSONObject().put("writes", new JSONArray().put(w)).toString();
            URL u = new URL("https://firestore.googleapis.com/v1/projects/" + proyek + "/databases/(default)/documents:commit?key=" + apiKey);
            c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
            c.getResponseCode();
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.disconnect();
        }
    }

    @Override
    public void onDestroy() {
        try { if (lm != null) lm.removeUpdates(this); } catch (Exception ignored) { }
        // hapus posisi dari peta saat dimatikan
        final Thread t = new Thread(() -> commit(false, 0, 0, 0));
        t.start();
        try { t.join(4000); } catch (InterruptedException ignored) { }
        ex.shutdown();
        super.onDestroy();
    }
}
