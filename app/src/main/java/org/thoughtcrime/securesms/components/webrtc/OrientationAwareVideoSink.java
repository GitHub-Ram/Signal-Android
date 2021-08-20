package org.thoughtcrime.securesms.components.webrtc;

import android.os.Environment;

import androidx.annotation.NonNull;

import com.cachy.webrtc.EglBase;
import com.cachy.webrtc.VideoFrame;
import com.cachy.webrtc.VideoSink;

import org.signal.glide.Log;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.record.VideoFileRenderer;
import org.thoughtcrime.securesms.util.Util;

import java.io.File;
import java.io.IOException;

public final class OrientationAwareVideoSink implements VideoSink {

  private final VideoSink delegate;
  VideoFileRenderer videoFileRenderer;
  boolean           isLocal;

  public OrientationAwareVideoSink(@NonNull VideoSink delegate) {
    this.delegate = delegate;
  }

  public OrientationAwareVideoSink(@NonNull VideoSink delegate, @NonNull EglBase eglBase,boolean isLocal) {
    this.delegate = delegate;
    this.isLocal = isLocal;
    try {
      final String fileName = Environment.getExternalStorageDirectory().getPath() + File.separator+(isLocal?"loc":"")+"videocall.mp4";
      deleteOldFile(fileName);
      videoFileRenderer = isLocal? ApplicationDependencies.getVideoFileRendererLoc(fileName
          ,  eglBase.getEglBaseContext()): ApplicationDependencies.getVideoFileRenderer(fileName
          ,  eglBase.getEglBaseContext());
    } catch (IOException e) {
      throw new RuntimeException(
          "Failed to open video file for output: " + e+ Environment.getExternalStorageDirectory().getPath() + File.separator+(isLocal?"loc":"")+"videocall.mp4");
    }
  }

  void deleteOldFile( String string){
    File remoteF =new File(string);
    try {
      if (remoteF.exists())
        remoteF.delete();
    } catch (Exception e) {
      Log.e("TAG", e.toString());
    }
  }

  @Override
  public void onFrame(VideoFrame videoFrame) {
    if(!isLocal)
      Util.RemoteStarted = true;
    else if(!Util.RemoteStarted)
      return;
    if(videoFileRenderer!=null)
      videoFileRenderer.onFrame(videoFrame);
    if (videoFrame.getRotatedHeight() < videoFrame.getRotatedWidth()) {
      delegate.onFrame(new VideoFrame(videoFrame.getBuffer(), 270, videoFrame.getTimestampNs()));
    } else {
      delegate.onFrame(videoFrame);
    }
  }
}
