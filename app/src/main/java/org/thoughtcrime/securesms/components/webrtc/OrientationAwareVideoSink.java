package org.thoughtcrime.securesms.components.webrtc;

import android.os.Environment;

import androidx.annotation.NonNull;

import com.cachy.webrtc.EglBase;
import com.cachy.webrtc.VideoFileRenderer;
import com.cachy.webrtc.VideoFrame;
import com.cachy.webrtc.VideoSink;

import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;

import java.io.File;
import java.io.IOException;

public final class OrientationAwareVideoSink implements VideoSink {

  private final VideoSink delegate;
  VideoFileRenderer videoFileRenderer;

  public OrientationAwareVideoSink(@NonNull VideoSink delegate) {
    this.delegate = delegate;
  }

  public OrientationAwareVideoSink(@NonNull VideoSink delegate, @NonNull EglBase eglBase,boolean isLocal) {
    this.delegate = delegate;
    try {
      final String fileName = Environment.getExternalStorageDirectory().getPath() + File.separator+(isLocal?"loc":"")+"videocall.mp4";
      videoFileRenderer = ApplicationDependencies.getVideoFileRenderer(fileName
          , 100, 200, eglBase.getEglBaseContext());
    } catch (IOException e) {
      throw new RuntimeException(
          "Failed to open video file for output: " + e+ Environment.getExternalStorageDirectory().getPath() + File.separator+(isLocal?"loc":"")+"videocall.mp4");
    }
  }

  @Override
  public void onFrame(VideoFrame videoFrame) {
    if(videoFileRenderer!=null)
      videoFileRenderer.onFrame(videoFrame);
    if (videoFrame.getRotatedHeight() < videoFrame.getRotatedWidth()) {
      delegate.onFrame(new VideoFrame(videoFrame.getBuffer(), 270, videoFrame.getTimestampNs()));
    } else {
      delegate.onFrame(videoFrame);
    }
  }
}
