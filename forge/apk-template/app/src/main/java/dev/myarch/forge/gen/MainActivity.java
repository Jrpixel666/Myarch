package dev.myarch.forge.gen;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.app.Activity;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Hosts the generated web app in a full-screen WebView.
 *
 * Everything the idea turned into lives in assets/index.html; this class only
 * wires up the capabilities a WebView does not grant on its own: JavaScript,
 * DOM storage, geolocation, and keeping navigation inside the app.
 *
 * NEEDS_LOCATION is substituted by the builder: true when the generated app
 * asked for the device location, false otherwise, so a timer or note app never
 * prompts for a permission it does not use.
 */
public class MainActivity extends Activity {

    private static final boolean NEEDS_LOCATION = {{NEEDS_LOCATION}};
    private static final int REQ_LOCATION = 1;

    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);

        // Keep links inside the WebView instead of handing off to a browser,
        // except for explicit external schemes (tel:, mailto:, geo:, intents).
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if (scheme != null && !scheme.equals("http") && !scheme.equals("https")
                        && !scheme.equals("file")) {
                    try {
                        startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, uri));
                        return true;
                    } catch (Exception ignored) {
                        return true;
                    }
                }
                return false;
            }
        });

        // Grant the page's own geolocation and media prompts; the OS-level
        // permission is still gated by the runtime request below.
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                    GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                request.grant(request.getResources());
            }
        });

        if (NEEDS_LOCATION && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
        }

        if (savedInstanceState == null) {
            web.loadUrl("file:///android_asset/index.html");
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        web.restoreState(savedInstanceState);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
