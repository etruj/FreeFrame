package local.photoframe;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Full-screen, always-on WebView showing the frame page, which the app's
 * own server (FrameService) serves from this tablet.
 */
public class MainActivity extends Activity {
    private static final String URL = "http://127.0.0.1:" + FrameApi.PORT + "/frame";
    private static final long CHECK_MS = 20 * 1000;
    // The page pings Frame.alive() through its brightness call every minute;
    // silence for this long means it is stuck or never loaded.
    private static final long STALE_MS = 3 * 60 * 1000;
    // If anything else (a system pop-up, another app) takes the screen, the
    // frame comes back by itself after this long. Long enough to use Settings.
    private static final long RETURN_MS = 3 * 60 * 1000;

    private static final String WAITING_PAGE =
            "<html><body style='background:#000;color:#888;font-family:sans-serif;"
            + "text-align:center;padding-top:25%;font-size:22px'>"
            + "Starting&hellip;</body></html>";

    private final Handler handler = new Handler();
    private WebView web;
    private boolean failed;
    private volatile long lastAlive;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        web = new WebView(this);
        web.setBackgroundColor(0xFF000000);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMediaPlaybackRequiresUserGesture(false);  // videos start by themselves
        web.addJavascriptInterface(new Bridge(), "Frame");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, int code, String description,
                    String failingUrl) {
                // Only the page itself failing matters; a missed photo or
                // API call is retried by the page.
                if (failingUrl != null && failingUrl.equals(URL)) {
                    failed = true;
                    view.loadDataWithBaseURL(null, WAITING_PAGE, "text/html", "utf-8", null);
                    handler.postDelayed(retry, 2000);
                }
            }
        });
        setContentView(web);

        startService(new Intent(this, FrameService.class));
        load();
        handler.postDelayed(watchdog, CHECK_MS);
    }

    private void load() {
        handler.removeCallbacks(retry);
        failed = false;
        lastAlive = SystemClock.elapsedRealtime();
        web.loadUrl(URL);
    }

    // The server takes a moment to come up after boot or an update.
    private final Runnable retry = new Runnable() {
        @Override
        public void run() {
            load();
        }
    };

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (failed || SystemClock.elapsedRealtime() - lastAlive > STALE_MS) {
                load();
            }
            handler.postDelayed(this, CHECK_MS);
        }
    };

    private final Runnable comeBack = new Runnable() {
        @Override
        public void run() {
            startActivity(new Intent(MainActivity.this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        }
    };

    @Override
    protected void onPause() {
        super.onPause();
        handler.postDelayed(comeBack, RETURN_MS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(comeBack);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    public void onBackPressed() {
        // Nothing to go back to.
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(watchdog);
        handler.removeCallbacks(comeBack);
        handler.removeCallbacks(retry);
        web.destroy();
        super.onDestroy();
    }

    /** What the page can ask of the tablet, as window.Frame. */
    private class Bridge {
        /** Backlight level, 0.01 (dimmest) to 1.0; below 0 follows the tablet's own brightness setting. */
        @JavascriptInterface
        public void setBrightness(final float level) {
            lastAlive = SystemClock.elapsedRealtime();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.screenBrightness = level < 0
                            ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                            : Math.max(0.01f, Math.min(1f, level));
                    getWindow().setAttributes(lp);
                }
            });
        }
    }
}
