package com.hanatrial.tripjepang;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/** Jembatan JS -> layanan lokasi latar belakang. Dipanggil dari halaman lewat Capacitor.Plugins.LokasiBg. */
@CapacitorPlugin(
    name = "LokasiBg",
    permissions = {
        @Permission(alias = "lokasi", strings = { Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION }),
        @Permission(alias = "notif", strings = { "android.permission.POST_NOTIFICATIONS" })
    }
)
public class LokasiBgPlugin extends Plugin {

    @PluginMethod
    public void start(PluginCall call) {
        if (getPermissionState("lokasi") != PermissionState.GRANTED) {
            requestPermissionForAlias("lokasi", call, "izinLokasi");
            return;
        }
        lanjutNotif(call);
    }

    @PermissionCallback
    private void izinLokasi(PluginCall call) {
        if (getPermissionState("lokasi") == PermissionState.GRANTED) {
            lanjutNotif(call);
        } else {
            call.reject("izin-lokasi-ditolak");
        }
    }

    private void lanjutNotif(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notif") != PermissionState.GRANTED) {
            requestPermissionForAlias("notif", call, "izinNotif");
            return;
        }
        mulai(call);
    }

    @PermissionCallback
    private void izinNotif(PluginCall call) {
        mulai(call);   // notifikasi boleh ditolak; layanan tetap jalan
    }

    private void mulai(PluginCall call) {
        Intent i = new Intent(getContext(), LokasiService.class);
        i.putExtra("proyek", call.getString("proyek", ""));
        i.putExtra("apiKey", call.getString("apiKey", ""));
        i.putExtra("trip", call.getString("trip", ""));
        i.putExtra("id", call.getString("id", ""));
        i.putExtra("nama", call.getString("nama", ""));
        try {
            ContextCompat.startForegroundService(getContext(), i);
            JSObject r = new JSObject();
            r.put("ok", true);
            call.resolve(r);
        } catch (Exception e) {
            call.reject("gagal-mulai: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        getContext().stopService(new Intent(getContext(), LokasiService.class));
        call.resolve();
    }

    /** Minta pengecualian hemat baterai supaya Android tidak mematikan layanan lokasi. */
    @PluginMethod
    public void abaikanBaterai(PluginCall call) {
        try {
            PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getContext().getPackageName())) {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getContext().getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(i);
            }
        } catch (Exception ignored) { }
        call.resolve();
    }
}
