package com.mobeen.englishteacher;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hosts the English Teacher page (assets/index.html) in a WebView.
 *
 * <p>Provides to the page: window.AndroidTTS (Android's own text-to-speech, because WebView has no
 * working speechSynthesis) and window.AndroidApp (call scheduling, incoming-call state, keep-alive
 * service, battery settings).
 */
public class MainActivity extends Activity implements TextToSpeech.OnInitListener {

  private static final int REQ = 100;
  private WebView webView;
  private TextToSpeech tts;
  private boolean ttsReady = false;
  private boolean serviceRunning = false;
  private volatile int incomingState = 0; // 0 none, 1 ringing, 2 answer immediately
  private final AtomicInteger pending = new AtomicInteger(0);
  private volatile long lastSpeechEnd = 0;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    if (Build.VERSION.SDK_INT >= 27) {
      setShowWhenLocked(true);
      setTurnScreenOn(true);
    }
    tts = new TextToSpeech(this, this);

    webView = new WebView(this);
    WebSettings s = webView.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true); // keeps the learner's data and API key
    s.setAllowFileAccess(true);
    s.setMediaPlaybackRequiresUserGesture(false);
    webView.addJavascriptInterface(new TtsBridge(), "AndroidTTS");
    webView.addJavascriptInterface(new AppBridge(), "AndroidApp");
    webView.setWebViewClient(new WebViewClient());
    webView.setWebChromeClient(
        new WebChromeClient() {
          @Override
          public void onPermissionRequest(final PermissionRequest request) {
            runOnUiThread(() -> request.grant(request.getResources()));
          }
        });
    setContentView(webView);
    handleIntent(getIntent());
    webView.loadUrl("file:///android_asset/index.html");
    askPermissions();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    handleIntent(intent);
    if (webView != null) {
      webView.evaluateJavascript("window.onIncoming&&window.onIncoming()", null);
    }
  }

  private void handleIntent(Intent i) {
    if (i != null && i.getBooleanExtra("incoming", false)) {
      incomingState = i.getBooleanExtra("answer", false) ? 2 : 1;
      i.removeExtra("incoming");
      i.removeExtra("answer");
    }
  }

  @Override
  public void onInit(int status) {
    if (status == TextToSpeech.SUCCESS) {
      ttsReady = true;
      tts.setLanguage(new Locale("hi", "IN"));
      tts.setOnUtteranceProgressListener(
          new UtteranceProgressListener() {
            @Override
            public void onStart(String id) {}

            @Override
            public void onDone(String id) {
              finished();
            }

            @Override
            public void onError(String id) {
              finished();
            }

            @Override
            public void onStop(String id, boolean interrupted) {
              finished();
            }
          });
    }
  }

  private void finished() {
    int v = pending.decrementAndGet();
    if (v < 0) pending.set(0);
    lastSpeechEnd = System.currentTimeMillis();
  }

  /** window.AndroidTTS.* used by the page to speak and to know whether speech is playing. */
  private class TtsBridge {
    @JavascriptInterface
    public void speak(String text, float rate) {
      if (!ttsReady || text == null || text.trim().isEmpty()) return;
      pending.incrementAndGet();
      final String id = "u" + System.nanoTime();
      runOnUiThread(
          () -> {
            boolean hasDevanagari = text.chars().anyMatch(c -> c >= 0x0900 && c <= 0x097F);
            tts.setLanguage(hasDevanagari ? new Locale("hi", "IN") : new Locale("en", "IN"));
            tts.setSpeechRate(rate > 0 ? rate : 1f);
            int r = tts.speak(text, TextToSpeech.QUEUE_ADD, null, id);
            if (r != TextToSpeech.SUCCESS) finished();
          });
    }

    @JavascriptInterface
    public void stop() {
      pending.set(0);
      runOnUiThread(
          () -> {
            if (ttsReady) tts.stop();
          });
    }

    @JavascriptInterface
    public boolean busy() {
      return pending.get() > 0 || System.currentTimeMillis() - lastSpeechEnd < 600;
    }
  }

  /** window.AndroidApp.* used by the page. */
  private class AppBridge {
    @JavascriptInterface
    public void setSchedule(String json) {
      Scheduler.saveSchedule(MainActivity.this, json);
    }

    @JavascriptInterface
    public double nextCall() {
      return (double) Scheduler.nextRegular(MainActivity.this);
    }

    @JavascriptInterface
    public int consumeIncoming() {
      int v = incomingState;
      incomingState = 0;
      return v;
    }

    @JavascriptInterface
    public void answered() {
      Scheduler.answered(MainActivity.this);
    }

    @JavascriptInterface
    public void declineCall() {
      Scheduler.declined(MainActivity.this);
    }

    @JavascriptInterface
    public void setCaller(String name) {
      Scheduler.prefs(MainActivity.this)
          .edit()
          .putString(Scheduler.KEY_CALLER, name == null ? "" : name)
          .apply();
    }

    @JavascriptInterface
    public void keepAlive(boolean on) {
      runOnUiThread(
          () -> {
            if (on) startKeepAlive();
            else stopKeepAlive();
          });
    }

    @JavascriptInterface
    public void batterySettings() {
      runOnUiThread(
          () -> {
            try {
              PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
              if (Build.VERSION.SDK_INT >= 23 && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
              } else {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
              }
            } catch (Exception e) {
              try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
              } catch (Exception ignored) {
              }
            }
          });
    }
  }

  private void askPermissions() {
    List<String> need = new ArrayList<>();
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      need.add(Manifest.permission.RECORD_AUDIO);
    }
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
      need.add(Manifest.permission.POST_NOTIFICATIONS);
    }
    if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ);
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
    super.onRequestPermissionsResult(code, perms, results);
  }

  private void startKeepAlive() {
    if (serviceRunning) return;
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      return;
    }
    try {
      Intent i = new Intent(this, KeepAliveService.class);
      if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
      else startService(i);
      serviceRunning = true;
    } catch (Exception ignored) {
    }
  }

  private void stopKeepAlive() {
    if (!serviceRunning) return;
    stopService(new Intent(this, KeepAliveService.class));
    serviceRunning = false;
  }

  @Override
  protected void onDestroy() {
    if (tts != null) {
      tts.stop();
      tts.shutdown();
    }
    stopKeepAlive();
    super.onDestroy();
  }
}
