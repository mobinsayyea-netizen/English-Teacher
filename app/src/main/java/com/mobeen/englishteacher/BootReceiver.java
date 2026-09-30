package com.mobeen.englishteacher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Re-arms the teacher's call schedule after a reboot or an app update. */
public class BootReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context ctx, Intent intent) {
    String a = intent.getAction();
    if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
      Scheduler.scheduleNext(ctx);
    }
  }
}
