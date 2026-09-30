package com.mobeen.englishteacher;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

/** Receives the scheduled alarm and shows the teacher's incoming-call notification. */
public class CallReceiver extends BroadcastReceiver {
  static final String ACTION_RING = "com.mobeen.englishteacher.RING";
  static final String ACTION_DECLINE = "com.mobeen.englishteacher.DECLINE";
  static final String ACTION_MISSED = "com.mobeen.englishteacher.MISSED";
  static final String CHANNEL = "teacher_call_v1";
  static final int NOTIF_ID = 42;

  @Override
  public void onReceive(Context ctx, Intent intent) {
    String a = intent.getAction();
    if (ACTION_RING.equals(a)) {
      int attempt = intent.getIntExtra("attempt", 1);
      if (attempt == 1) Scheduler.scheduleNext(ctx);
      ring(ctx, attempt);
    } else if (ACTION_DECLINE.equals(a)) {
      Scheduler.declined(ctx);
    } else if (ACTION_MISSED.equals(a)) {
      Scheduler.missed(ctx, intent.getLongExtra("ring_id", 0));
    }
  }

  private void ring(Context ctx, int attempt) {
    NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
    Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
    if (Build.VERSION.SDK_INT >= 26) {
      NotificationChannel ch =
          new NotificationChannel(CHANNEL, "Teacher calls", NotificationManager.IMPORTANCE_HIGH);
      ch.setDescription("Your English teacher calling at the scheduled time");
      ch.setSound(
          sound,
          new AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
              .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
              .build());
      ch.enableVibration(true);
      ch.setVibrationPattern(new long[] {0, 700, 500, 700});
      ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
      nm.createNotificationChannel(ch);
    }

    long ringId = System.currentTimeMillis();
    Scheduler.prefs(ctx)
        .edit()
        .putBoolean(Scheduler.KEY_RING_ACTIVE, true)
        .putInt(Scheduler.KEY_RING_ATTEMPT, attempt)
        .putLong(Scheduler.KEY_RING_ID, ringId)
        .apply();

    int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
    Intent open = new Intent(ctx, MainActivity.class);
    open.putExtra("incoming", true);
    open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    PendingIntent openPi = PendingIntent.getActivity(ctx, 10, open, flags);

    Intent answer = new Intent(ctx, MainActivity.class);
    answer.putExtra("incoming", true);
    answer.putExtra("answer", true);
    answer.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    PendingIntent answerPi = PendingIntent.getActivity(ctx, 11, answer, flags);

    Intent decline = new Intent(ctx, CallReceiver.class);
    decline.setAction(ACTION_DECLINE);
    PendingIntent declinePi = PendingIntent.getBroadcast(ctx, 12, decline, flags);

    String who = Scheduler.prefs(ctx).getString(Scheduler.KEY_CALLER, "");
    String title = (who == null || who.trim().isEmpty() ? "Your English teacher" : who) + " is calling";

    Notification.Builder b =
        Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(ctx, CHANNEL)
            : new Notification.Builder(ctx).setSound(sound).setPriority(Notification.PRIORITY_MAX);
    b.setSmallIcon(android.R.drawable.sym_call_incoming)
        .setContentTitle(title)
        .setContentText("Time for your English lesson")
        .setCategory(Notification.CATEGORY_CALL)
        .setContentIntent(openPi)
        .setFullScreenIntent(openPi, true)
        .setAutoCancel(true)
        .addAction(android.R.drawable.sym_action_call, "Answer", answerPi)
        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Not now", declinePi);
    if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(60000);
    Notification n = b.build();
    n.flags |= Notification.FLAG_INSISTENT;
    nm.notify(NOTIF_ID, n);

    // If nobody reacts, clean up and try once more later.
    Intent missed = new Intent(ctx, CallReceiver.class);
    missed.setAction(ACTION_MISSED);
    missed.putExtra("ring_id", ringId);
    PendingIntent missedPi = PendingIntent.getBroadcast(ctx, 13, missed, flags);
    AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ringId + 65000, missedPi);
  }
}
