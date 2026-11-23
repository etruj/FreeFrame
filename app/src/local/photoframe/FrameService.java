package local.photoframe;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Keeps the tablet's web server (the frame page and the photo manager) running. */
public class FrameService extends Service {
    private HttpServer server;
    // Keeps the Wi-Fi chip out of power saving, which otherwise made the
    // tablet answer in seconds (slow uploads from phones, flaky remote access).
    private android.net.wifi.WifiManager.WifiLock wifiLock;

    @Override
    public void onCreate() {
        super.onCreate();
        server = new HttpServer(FrameApi.PORT, new FrameApi(this));
        server.start();
        android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext()
                .getSystemService(WIFI_SERVICE);
        wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "frame");
        wifiLock.setReferenceCounted(false);
        wifiLock.acquire();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        server.stop();
        if (wifiLock != null) {
            wifiLock.release();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
