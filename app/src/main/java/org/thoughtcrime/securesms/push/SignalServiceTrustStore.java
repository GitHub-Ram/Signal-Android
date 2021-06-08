package org.thoughtcrime.securesms.push;

import android.content.Context;

import org.thoughtcrime.securesms.BuildConfig;
import org.thoughtcrime.securesms.R;
import org.whispersystems.signalservice.api.push.TrustStore;

import java.io.InputStream;

public class SignalServiceTrustStore implements TrustStore {

  private final Context context;

  public SignalServiceTrustStore(Context context) {
    this.context = context.getApplicationContext();
  }

  @Override
  public InputStream getKeyStoreInputStream() {
    if(!BuildConfig.STAGE_ENABLED)
      return context.getResources().openRawResource(R.raw.cachy);
    else return context.getResources().openRawResource(R.raw.whisper);
  }

  @Override
  public String getKeyStorePassword() {
    return "whisper";
  }
}
