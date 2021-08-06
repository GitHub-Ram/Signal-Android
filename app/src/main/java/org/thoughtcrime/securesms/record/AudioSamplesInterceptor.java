package org.thoughtcrime.securesms.record;

import android.annotation.SuppressLint;


import org.webrtc.audio.JavaAudioDeviceModule;

import java.util.HashMap;

/** JavaAudioDeviceModule allows attaching samples callback only on building
 *  We don't want to instantiate VideoFileRenderer and codecs at this step
 *  It's simple dummy class, it does nothing until samples are necessary */
@SuppressWarnings("WeakerAccess")
public class AudioSamplesInterceptor implements JavaAudioDeviceModule.SamplesReadyCallback {

  @SuppressLint("UseSparseArrays")
  protected final HashMap<Integer, JavaAudioDeviceModule.SamplesReadyCallback> callbacks = new HashMap<>();

  @Override
  public void onWebRtcAudioRecordSamplesReady(JavaAudioDeviceModule.AudioSamples audioSamples) {
    for (JavaAudioDeviceModule.SamplesReadyCallback callback : callbacks.values()) {
      callback.onWebRtcAudioRecordSamplesReady(audioSamples);
    }
  }

  public void attachCallback(Integer id, JavaAudioDeviceModule.SamplesReadyCallback callback) throws Exception {
    callbacks.put(id, callback);
  }

  public void detachCallback(Integer id) {
    callbacks.remove(id);
  }

}