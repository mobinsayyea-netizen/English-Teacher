package com.mobeen.englishteacher;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.util.Calendar;
import org.json.JSONArray;
import org.json.JSONObject;

/** Schedules the teacher's incoming "calls" with alarm-clock alarms and handles retries. */
final class Scheduler {
  static final String PREFS = "teacher_prefs";
  static final String KEY_SCHEDULE = "schedule";
  static final String KEY_RING_ACTIVE = "ring_active";
  static final String KEY_RING_ATTEMPT = "ring_attempt";
  static final String KEY_RING_ID = "ring_id";
  static final String KEY_CALLER = "caller";
  static final int RC_MAIN = 1;
  static final int RC_RETRY = 2;
  static final int RETRY_MINUTES = 10;

  private Scheduler() {}

  static SharedPreferences prefs(Context c) {
    return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  /** JSON like [{"days":[1,2,3,4,5,6,7],"h":19,"m":0}] where days use Calendar.DAY_OF_WEEK (Sun=1). */
  static void saveSchedule(Context c, String json) {
    prefs(c).edit().putString(KEY_SCHEDULE, json == null ? "[]" : json).apply();
    scheduleNext(c);
  }

  static long nextRegular(Context c) {
    try {
      JSONArray arr = new JSONArray(prefs(c).getString(KEY_SCHEDULE, "[]"));
      long best = 0;
      long now = System.currentTimeMillis();
      for (int i = 0; i < arr.length(); i++) {
        JSONObject e = arr.getJSONObject(i);
        JSONArray days = e.getJSONArray("days");
        int h = e.getInt("h");
        int m = e.getInt("m");
        for (int add = 0; add <= 7; add++) {
          Calendar cal = Calendar.getInstance();
          cal.add(Calendar.DAY_OF_YEAR, add);
          cal.set(Calendar.HOUR_OF_DAY, h);
          cal.set(Calendar.MINUTE, m);
          cal.set(Calendar.SECOND, 0);
          cal.set(Calendar.MILLISECOND, 0);
          if (cal.getTimeInMillis() <= now + 1000) continue;
          int dow = cal.get(Calendar.DAY_OF_WEEK);
          boolean ok = false;
          for (int d = 0; d < days.length(); d++) {
            if (days.getInt(d) == dow) ok = true;
          }
          if (ok) {
            if (best == 0 || cal.getTimeInMillis() < best) best = cal.getTimeInMillis();
            break;
          }
        }
      }
      return best;
    } catch (Exception ex) {
      return 0;
    }
  }

  private static PendingIntent ringIntent(Context c, int requestCode, int attempt) {
    Intent i = new Intent(c, CallReceiver.class);
    i.setAction(CallReceiver.ACTION_RING);
    i.putExtra("attempt", attempt);
    return PendingIntent.getBroadcast(
        c, requestCode, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }

  private static PendingIntent showIntent(Context c) {
    Intent i = new Intent(c, MainActivity.class);
    return PendingIntent.getActivity(
        c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }

  static void scheduleNext(Context c) {
    AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
    PendingIntent pi = ringIntent(c, RC_MAIN, 1);
    long t = nextRegular(c);
    if (t == 0) {
      am.cancel(pi);
      return;
    }
    am.setAlarmClock(new AlarmManager.AlarmClockInfo(t, showIntent(c)), pi);
  }

  static void scheduleRetry(Context c, long delayMs) {
    AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
    long t = System.currentTimeMillis() + delayMs;
    am.setAlarmClock(new AlarmManager.AlarmClockInfo(t, showIntent(c)), ringIntent(c, RC_RETRY, 2));
  }

  private static void stopRinging(Context c) {
    NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    nm.cancel(CallReceiver.NOTIF_ID);
  }

  /** The user picked up the call. */
  static void answered(Context c) {
    prefs(c).edit().putBoolean(KEY_RING_ACTIVE, false).apply();
    stopRinging(c);
  }

  /** The user pressed "Not now" or the ring timed out: try once more after a short while. */
  static void declined(Context c) {
    SharedPreferences p = prefs(c);
    boolean wasActive = p.getBoolean(KEY_RING_ACTIVE, false);
    int attempt = p.getInt(KEY_RING_ATTEMPT, 1);
    p.edit().putBoolean(KEY_RING_ACTIVE, false).apply();
    stopRinging(c);
    if (wasActive && attempt == 1) scheduleRetry(c, RETRY_MINUTES * 60L * 1000L);
  }

  static void missed(Context c, long ringId) {
    SharedPreferences p = prefs(c);
    if (p.getBoolean(KEY_RING_ACTIVE, false) && p.getLong(KEY_RING_ID, 0) == ringId) {
      declined(c);
    }
  }
}
