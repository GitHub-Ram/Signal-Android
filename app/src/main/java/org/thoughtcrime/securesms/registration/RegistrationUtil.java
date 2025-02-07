package org.thoughtcrime.securesms.registration;

import android.content.Context;

import androidx.annotation.NonNull;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobs.DirectoryRefreshJob;
import org.thoughtcrime.securesms.jobs.StorageSyncJob;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.TextSecurePreferences;

public final class RegistrationUtil {

  private static final String TAG = Log.tag(RegistrationUtil.class);

  private RegistrationUtil() {}

  /**
   * There's several events where a registration may or may not be considered complete based on what
   * path a user has taken. This will only truly mark registration as complete if all of the
   * requirements are met.
   */
  public static void maybeMarkRegistrationComplete(@NonNull Context context) {
    try {
      Log.w(TAG + " RAM", "isRegistrationComplete:" + SignalStore.registrationValues().isRegistrationComplete() + " isPushRegistered:" + TextSecurePreferences.isPushRegistered(context));
      Log.w(TAG + " RAM", "isEmpty:" + Recipient.self().getProfileName().isEmpty() + " hasPin:" + (SignalStore.kbsValues().hasPin() || SignalStore.kbsValues().hasOptedOut()));
    }catch (Exception ex){}
    if (!SignalStore.registrationValues().isRegistrationComplete() &&
        TextSecurePreferences.isPushRegistered(context)            &&
        !Recipient.self().getProfileName().isEmpty()               &&
        (SignalStore.kbsValues().hasPin() || SignalStore.kbsValues().hasOptedOut()))
    {

      Log.i(TAG, "Marking registration completed.", new Throwable());
      SignalStore.registrationValues().setRegistrationComplete();
      ApplicationDependencies.getJobManager().startChain(new StorageSyncJob())
                                             .then(new DirectoryRefreshJob(false))
                                             .enqueue();
    } else if (!SignalStore.registrationValues().isRegistrationComplete()) {

      Log.i(TAG, "Registration is not yet complete.", new Throwable());
    }
  }
}
