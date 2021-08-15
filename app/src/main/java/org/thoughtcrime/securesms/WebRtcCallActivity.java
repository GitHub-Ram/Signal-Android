/*
 * Copyright (C) 2016 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.thoughtcrime.securesms;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.DisplayMetrics;
import android.util.Rational;
import android.util.SparseIntArray;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProviders;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.components.TooltipPopup;
import org.thoughtcrime.securesms.components.sensors.DeviceOrientationMonitor;
import org.thoughtcrime.securesms.components.webrtc.CallParticipantsListUpdatePopupWindow;
import org.thoughtcrime.securesms.components.webrtc.CallParticipantsState;
import org.thoughtcrime.securesms.components.webrtc.GroupCallSafetyNumberChangeNotificationUtil;
import org.thoughtcrime.securesms.components.webrtc.WebRtcAudioOutput;
import org.thoughtcrime.securesms.components.webrtc.WebRtcCallView;
import org.thoughtcrime.securesms.components.webrtc.WebRtcCallViewModel;
import org.thoughtcrime.securesms.components.webrtc.participantslist.CallParticipantsListDialog;
import org.thoughtcrime.securesms.conversation.ui.error.SafetyNumberChangeDialog;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.events.WebRtcViewModel;
import org.thoughtcrime.securesms.messagerequests.CalleeMustAcceptMessageRequestActivity;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.service.webrtc.SignalCallManager;
import org.thoughtcrime.securesms.sms.MessageSender;
import org.thoughtcrime.securesms.util.EllapsedTimeFormatter;
import org.thoughtcrime.securesms.util.FullscreenHelper;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.Util;
import com.cachy.webrtc.MediaStreamTrack;
import com.cachy.webrtc.VideoTrack;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.signalservice.api.messages.calls.HangupMessage;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;

public class WebRtcCallActivity extends BaseActivity implements SafetyNumberChangeDialog.Callback {

  private static final String TAG = Log.tag(WebRtcCallActivity.class);

  private static final int STANDARD_DELAY_FINISH = 1000;

  public static final String ANSWER_ACTION   = WebRtcCallActivity.class.getCanonicalName() + ".ANSWER_ACTION";
  public static final String DENY_ACTION     = WebRtcCallActivity.class.getCanonicalName() + ".DENY_ACTION";
  public static final String END_CALL_ACTION = WebRtcCallActivity.class.getCanonicalName() + ".END_CALL_ACTION";

  public static final String EXTRA_ENABLE_VIDEO_IF_AVAILABLE = WebRtcCallActivity.class.getCanonicalName() + ".ENABLE_VIDEO_IF_AVAILABLE";

  private CallParticipantsListUpdatePopupWindow participantUpdateWindow;
  private DeviceOrientationMonitor              deviceOrientationMonitor;

  private FullscreenHelper    fullscreenHelper;
  private WebRtcCallView      callScreen;
  private TooltipPopup        videoTooltip;
  private WebRtcCallViewModel viewModel;
  private boolean             enableVideoIfAvailable;

  @Override
  protected void attachBaseContext(@NonNull Context newBase) {
    getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_YES);
    super.attachBaseContext(newBase);
  }

  @Override
  public void onCreate(Bundle savedInstanceState) {
    Log.i(TAG, "onCreate()");
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    super.onCreate(savedInstanceState);

    requestWindowFeature(Window.FEATURE_NO_TITLE);
    setContentView(R.layout.webrtc_call_activity);

    fullscreenHelper = new FullscreenHelper(this);

    setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);

    initializeResources();
    initializeViewModel();

    processIntent(getIntent());

    enableVideoIfAvailable = getIntent().getBooleanExtra(EXTRA_ENABLE_VIDEO_IF_AVAILABLE, false);
    getIntent().removeExtra(EXTRA_ENABLE_VIDEO_IF_AVAILABLE);
  }

  @Override
  public void onResume() {
    Log.i(TAG, "onResume()");
    super.onResume();
    initializeScreenshotSecurity();

    if (!EventBus.getDefault().isRegistered(this)) {
      EventBus.getDefault().register(this);
    }
  }

  @Override
  public void onNewIntent(Intent intent) {
    Log.i(TAG, "onNewIntent");
    super.onNewIntent(intent);
    processIntent(intent);
  }

  @Override
  public void onPause() {
    Log.i(TAG, "onPause");
    super.onPause();

    if (!isInPipMode() || isFinishing()) {
      EventBus.getDefault().unregister(this);
    }

    if (!viewModel.isCallStarting()) {
      CallParticipantsState state = viewModel.getCallParticipantsState().getValue();
      if (state != null && state.getCallState().isPreJoinOrNetworkUnavailable()) {
        finish();
      }
    }
  }

  @Override
  protected void onStop() {
    Log.i(TAG, "onStop");
    super.onStop();

    if (!isInPipMode() || isFinishing()) {
      EventBus.getDefault().unregister(this);
    }

    if (!viewModel.isCallStarting()) {
      CallParticipantsState state = viewModel.getCallParticipantsState().getValue();
      if (state != null && state.getCallState().isPreJoinOrNetworkUnavailable()) {
        ApplicationDependencies.getSignalCallManager().cancelPreJoin();
      }
    }
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    EventBus.getDefault().unregister(this);
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
  }

  @Override
  protected void onUserLeaveHint() {
    enterPipModeIfPossible();
  }

  @Override
  public void onBackPressed() {
    if (!enterPipModeIfPossible()) {
      super.onBackPressed();
    }
  }

  @Override
  public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
    viewModel.setIsInPipMode(isInPictureInPictureMode);
    participantUpdateWindow.setEnabled(!isInPictureInPictureMode);
  }

  private boolean enterPipModeIfPossible() {
    if (viewModel.canEnterPipMode() && isSystemPipEnabledAndAvailable()) {
      PictureInPictureParams params = new PictureInPictureParams.Builder()
              .setAspectRatio(new Rational(9, 16))
              .build();
      enterPictureInPictureMode(params);
      CallParticipantsListDialog.dismiss(getSupportFragmentManager());

      return true;
    }
    return false;
  }

  private boolean isInPipMode() {
    return isSystemPipEnabledAndAvailable() && isInPictureInPictureMode();
  }

  private void processIntent(@NonNull Intent intent) {
    if (ANSWER_ACTION.equals(intent.getAction())) {
      viewModel.setRecipient(EventBus.getDefault().getStickyEvent(WebRtcViewModel.class).getRecipient());
      handleAnswerWithAudio();
    } else if (DENY_ACTION.equals(intent.getAction())) {
      handleDenyCall();
    } else if (END_CALL_ACTION.equals(intent.getAction())) {
      handleEndCall();
    }
  }

  private void initializeScreenshotSecurity() {
    if (TextSecurePreferences.isScreenSecurityEnabled(this)) {
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    } else {
      getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
  }

  private void initializeResources() {
    callScreen = findViewById(R.id.callScreen);
    callScreen.setControlsListener(new ControlsListener());

    participantUpdateWindow = new CallParticipantsListUpdatePopupWindow(callScreen);
  }

  private void initializeViewModel() {
    deviceOrientationMonitor = new DeviceOrientationMonitor(this);
    getLifecycle().addObserver(deviceOrientationMonitor);

    WebRtcCallViewModel.Factory factory = new WebRtcCallViewModel.Factory(deviceOrientationMonitor);

    viewModel = ViewModelProviders.of(this, factory).get(WebRtcCallViewModel.class);
    viewModel.setIsInPipMode(isInPipMode());
    viewModel.getMicrophoneEnabled().observe(this, callScreen::setMicEnabled);
    viewModel.getWebRtcControls().observe(this, callScreen::setWebRtcControls);
    viewModel.getEvents().observe(this, this::handleViewModelEvent);
    viewModel.getCallTime().observe(this, this::handleCallTime);
    viewModel.getCallParticipantsState().observe(this, callScreen::updateCallParticipants);
    viewModel.getCallParticipantListUpdate().observe(this, participantUpdateWindow::addCallParticipantListUpdate);
    viewModel.getSafetyNumberChangeEvent().observe(this, this::handleSafetyNumberChangeEvent);
    viewModel.getGroupMembers().observe(this, unused -> updateGroupMembersForGroupCall());
    viewModel.shouldShowSpeakerHint().observe(this, this::updateSpeakerHint);

    callScreen.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
      CallParticipantsState state = viewModel.getCallParticipantsState().getValue();
      if (state != null) {
        if (state.needsNewRequestSizes()) {
          ApplicationDependencies.getSignalCallManager().updateRenderedResolutions();
        }
      }
    });

    viewModel.getOrientation().observe(this, orientation -> {
      ApplicationDependencies.getSignalCallManager().orientationChanged(orientation.getDegrees());

      switch (orientation) {
        case LANDSCAPE_LEFT_EDGE:
          callScreen.rotateControls(90);
          break;
        case LANDSCAPE_RIGHT_EDGE:
          callScreen.rotateControls(-90);
          break;
        case PORTRAIT_BOTTOM_EDGE:
          callScreen.rotateControls(0);
      }
    });
  }

  private void handleViewModelEvent(@NonNull WebRtcCallViewModel.Event event) {
    if (event instanceof WebRtcCallViewModel.Event.StartCall) {
      startCall(((WebRtcCallViewModel.Event.StartCall)event).isVideoCall());
      return;
    } else if (event instanceof WebRtcCallViewModel.Event.ShowGroupCallSafetyNumberChange) {
      SafetyNumberChangeDialog.showForGroupCall(getSupportFragmentManager(), ((WebRtcCallViewModel.Event.ShowGroupCallSafetyNumberChange) event).getIdentityRecords());
      return;
    }

    if (isInPipMode()) {
      return;
    }

    if (event instanceof WebRtcCallViewModel.Event.ShowVideoTooltip) {
      if (videoTooltip == null) {
        videoTooltip = TooltipPopup.forTarget(callScreen.getVideoTooltipTarget())
                                   .setBackgroundTint(ContextCompat.getColor(this, R.color.core_ultramarine))
                                   .setTextColor(ContextCompat.getColor(this, R.color.core_white))
                                   .setText(R.string.WebRtcCallActivity__tap_here_to_turn_on_your_video)
                                   .setOnDismissListener(() -> viewModel.onDismissedVideoTooltip())
                                   .show(TooltipPopup.POSITION_ABOVE);
        return;
      }
    } else if (event instanceof WebRtcCallViewModel.Event.DismissVideoTooltip) {
      if (videoTooltip != null) {
        videoTooltip.dismiss();
        videoTooltip = null;
      }
    } else {
      throw new IllegalArgumentException("Unknown event: " + event);
    }
  }

  private void handleCallTime(long callTime) {
    EllapsedTimeFormatter ellapsedTimeFormatter = EllapsedTimeFormatter.fromDurationMillis(callTime);

    if (ellapsedTimeFormatter == null) {
      return;
    }

    callScreen.setStatus(getString(R.string.WebRtcCallActivity__signal_s, ellapsedTimeFormatter.toString()));
  }

  private void handleSetAudioHandset() {
    ApplicationDependencies.getSignalCallManager().setAudioSpeaker(false);
  }

  private void handleSetAudioSpeaker() {
    ApplicationDependencies.getSignalCallManager().setAudioSpeaker(true);
  }

  private void handleSetAudioBluetooth() {
    ApplicationDependencies.getSignalCallManager().setAudioBluetooth(true);
  }

  private void handleSetMuteAudio(boolean enabled) {
    ApplicationDependencies.getSignalCallManager().setMuteAudio(enabled);
  }

  private void handleSetMuteVideo(boolean muted) {
    Recipient recipient = viewModel.getRecipient().get();

    if (!recipient.equals(Recipient.UNKNOWN)) {
      String recipientDisplayName = recipient.getDisplayName(this);

      Permissions.with(this)
                 .request(Manifest.permission.CAMERA)
                 .ifNecessary()
                 .withRationaleDialog(getString(R.string.WebRtcCallActivity__to_call_s_signal_needs_access_to_your_camera, recipientDisplayName), R.drawable.ic_video_solid_24_tinted)
                 .withPermanentDenialDialog(getString(R.string.WebRtcCallActivity__to_call_s_signal_needs_access_to_your_camera, recipientDisplayName))
                 .onAllGranted(() -> ApplicationDependencies.getSignalCallManager().setMuteVideo(!muted))
                 .execute();
    }
  }

  private void handleFlipCamera() {
    ApplicationDependencies.getSignalCallManager().flipCamera();
  }

  private void handleAnswerWithAudio() {
    Recipient recipient = viewModel.getRecipient().get();

    if (!recipient.equals(Recipient.UNKNOWN)) {
      Permissions.with(this)
                 .request(Manifest.permission.RECORD_AUDIO)
                 .ifNecessary()
                 .withRationaleDialog(getString(R.string.WebRtcCallActivity_to_answer_the_call_from_s_give_signal_access_to_your_microphone, recipient.getDisplayName(this)),
                                      R.drawable.ic_mic_solid_24)
                 .withPermanentDenialDialog(getString(R.string.WebRtcCallActivity_signal_requires_microphone_and_camera_permissions_in_order_to_make_or_receive_calls))
                 .onAllGranted(() -> {
                   callScreen.setRecipient(recipient);
                   callScreen.setStatus(getString(R.string.RedPhone_answering));

                   ApplicationDependencies.getSignalCallManager().acceptCall(false);
                 })
                 .onAnyDenied(this::handleDenyCall)
                 .execute();
    }
  }

  private void handleAnswerWithVideo() {
    Recipient recipient = viewModel.getRecipient().get();

    if (!recipient.equals(Recipient.UNKNOWN)) {
      Permissions.with(this)
                 .request(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                 .ifNecessary()
                 .withRationaleDialog(getString(R.string.WebRtcCallActivity_to_answer_the_call_from_s_give_signal_access_to_your_microphone, recipient.getDisplayName(this)),
                     R.drawable.ic_mic_solid_24, R.drawable.ic_video_solid_24_tinted)
                 .withPermanentDenialDialog(getString(R.string.WebRtcCallActivity_signal_requires_microphone_and_camera_permissions_in_order_to_make_or_receive_calls))
                 .onAllGranted(() -> {
                   callScreen.setRecipient(recipient);
                   callScreen.setStatus(getString(R.string.RedPhone_answering));

                   ApplicationDependencies.getSignalCallManager().acceptCall(true);

                   handleSetMuteVideo(false);
                 })
                 .onAnyDenied(this::handleDenyCall)
                 .execute();
    }
  }

  private void handleDenyCall() {
    Recipient recipient = viewModel.getRecipient().get();

    if (!recipient.equals(Recipient.UNKNOWN)) {
      ApplicationDependencies.getSignalCallManager().denyCall();

      callScreen.setRecipient(recipient);
      callScreen.setStatus(getString(R.string.RedPhone_ending_call));
      delayedFinish();
    }
  }

  private void handleEndCall() {
    Log.i(TAG, "Hangup pressed, handling termination now...");
    ApplicationDependencies.getSignalCallManager().localHangup();
  }

  private void handleOutgoingCall(@NonNull WebRtcViewModel event) {
    if (event.getGroupState().isNotIdle()) {
      callScreen.setStatusFromGroupCallState(event.getGroupState());
    } else {
      callScreen.setStatus(getString(R.string.WebRtcCallActivity__calling));
    }
  }

  private void handleTerminate(@NonNull Recipient recipient, @NonNull HangupMessage.Type hangupType) {
    Log.i(TAG, "handleTerminate called: " + hangupType.name());

    callScreen.setStatusFromHangupType(hangupType);

    EventBus.getDefault().removeStickyEvent(WebRtcViewModel.class);

    if (hangupType == HangupMessage.Type.NEED_PERMISSION) {
      startActivity(CalleeMustAcceptMessageRequestActivity.createIntent(this, recipient.getId()));
    }
    //stopRecording();
    delayedFinish();

  }

  private void handleCallRinging() {
    callScreen.setStatus(getString(R.string.RedPhone_ringing));
  }

  private void handleCallBusy() {
    EventBus.getDefault().removeStickyEvent(WebRtcViewModel.class);
    callScreen.setStatus(getString(R.string.RedPhone_busy));
    delayedFinish(SignalCallManager.BUSY_TONE_LENGTH);
  }

  private void handleCallConnected(@NonNull WebRtcViewModel event) {
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES);
    if (event.getGroupState().isNotIdleOrConnected()) {
      callScreen.setStatusFromGroupCallState(event.getGroupState());
    }
    //startRecording();
  }

  private void handleRecipientUnavailable() {
    EventBus.getDefault().removeStickyEvent(WebRtcViewModel.class);
    callScreen.setStatus(getString(R.string.RedPhone_recipient_unavailable));
    delayedFinish();
  }

  private void handleServerFailure() {
    EventBus.getDefault().removeStickyEvent(WebRtcViewModel.class);
    callScreen.setStatus(getString(R.string.RedPhone_network_failed));
  }

  private void handleNoSuchUser(final @NonNull WebRtcViewModel event) {
    if (isFinishing()) return; // XXX Stuart added this check above, not sure why, so I'm repeating in ignorance. - moxie
    new AlertDialog.Builder(this)
                   .setTitle(R.string.RedPhone_number_not_registered)
                   .setIcon(R.drawable.ic_warning)
                   .setMessage(R.string.RedPhone_the_number_you_dialed_does_not_support_secure_voice)
                   .setCancelable(true)
                   .setPositiveButton(R.string.RedPhone_got_it, (d, w) -> handleTerminate(event.getRecipient(), HangupMessage.Type.NORMAL))
                   .setOnCancelListener(d -> handleTerminate(event.getRecipient(), HangupMessage.Type.NORMAL))
                   .show();
  }

  private void handleUntrustedIdentity(@NonNull WebRtcViewModel event) {
    final IdentityKey theirKey  = event.getRemoteParticipants().get(0).getIdentityKey();
    final Recipient   recipient = event.getRemoteParticipants().get(0).getRecipient();

    if (theirKey == null) {
      Log.w(TAG, "Untrusted identity without an identity key, terminating call.");
      handleTerminate(recipient, HangupMessage.Type.NORMAL);
    }

    SafetyNumberChangeDialog.showForCall(getSupportFragmentManager(), recipient.getId());
  }

  public void handleSafetyNumberChangeEvent(@NonNull WebRtcCallViewModel.SafetyNumberChangeEvent safetyNumberChangeEvent) {
    if (Util.hasItems(safetyNumberChangeEvent.getRecipientIds())) {
      if (safetyNumberChangeEvent.isInPipMode()) {
        GroupCallSafetyNumberChangeNotificationUtil.showNotification(this, viewModel.getRecipient().get());
      } else {
        GroupCallSafetyNumberChangeNotificationUtil.cancelNotification(this, viewModel.getRecipient().get());
        SafetyNumberChangeDialog.showForDuringGroupCall(getSupportFragmentManager(), safetyNumberChangeEvent.getRecipientIds());
      }
    }
  }

  private void updateGroupMembersForGroupCall() {
    ApplicationDependencies.getSignalCallManager().requestUpdateGroupMembers();
  }

  private void updateSpeakerHint(boolean showSpeakerHint) {
    if (showSpeakerHint) {
      callScreen.showSpeakerViewHint();
    } else {
      callScreen.hideSpeakerViewHint();
    }
  }

  @Override
  public void onSendAnywayAfterSafetyNumberChange(@NonNull List<RecipientId> changedRecipients) {
    CallParticipantsState state = viewModel.getCallParticipantsState().getValue();

    if (state == null) {
      return;
    }

    if (state.getGroupCallState().isConnected()) {
      ApplicationDependencies.getSignalCallManager().groupApproveSafetyChange(changedRecipients);
    } else {
      viewModel.startCall(state.getLocalParticipant().isVideoEnabled());
    }
  }

  @Override
  public void onMessageResentAfterSafetyNumberChange() { }

  @Override
  public void onCanceled() {
    CallParticipantsState state = viewModel.getCallParticipantsState().getValue();
    if (state != null && state.getGroupCallState().isNotIdle()) {
      if (state.getCallState().isPreJoinOrNetworkUnavailable()) {
        ApplicationDependencies.getSignalCallManager().cancelPreJoin();
        finish();
      } else {
        handleEndCall();
      }
    } else {
      handleTerminate(viewModel.getRecipient().get(), HangupMessage.Type.NORMAL);
    }
  }

  private boolean isSystemPipEnabledAndAvailable() {
    return Build.VERSION.SDK_INT >= 26 &&
           getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
  }

  private void delayedFinish() {
    delayedFinish(STANDARD_DELAY_FINISH);
  }

  private void delayedFinish(int delayMillis) {
    callScreen.postDelayed(WebRtcCallActivity.this::finish, delayMillis);
  }

  @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
  public void onEventMainThread(@NonNull WebRtcViewModel event) {
    Log.i(TAG, "Got message from service: " + event);

    viewModel.setRecipient(event.getRecipient());
    callScreen.setRecipient(event.getRecipient());

    switch (event.getState()) {
      case CALL_PRE_JOIN:           handleCallPreJoin(event);                                                  break;
      case CALL_CONNECTED:          handleCallConnected(event);                                                break;
      case NETWORK_FAILURE:         handleServerFailure();                                                     break;
      case CALL_RINGING:            handleCallRinging();                                                       break;
      case CALL_DISCONNECTED:       handleTerminate(event.getRecipient(), HangupMessage.Type.NORMAL);          break;
      case CALL_ACCEPTED_ELSEWHERE: handleTerminate(event.getRecipient(), HangupMessage.Type.ACCEPTED);        break;
      case CALL_DECLINED_ELSEWHERE: handleTerminate(event.getRecipient(), HangupMessage.Type.DECLINED);        break;
      case CALL_ONGOING_ELSEWHERE:  handleTerminate(event.getRecipient(), HangupMessage.Type.BUSY);            break;
      case CALL_NEEDS_PERMISSION:   handleTerminate(event.getRecipient(), HangupMessage.Type.NEED_PERMISSION); break;
      case NO_SUCH_USER:            handleNoSuchUser(event);                                                   break;
      case RECIPIENT_UNAVAILABLE:   handleRecipientUnavailable();                                              break;
      case CALL_OUTGOING:           handleOutgoingCall(event);                                                 break;
      case CALL_BUSY:               handleCallBusy();                                                          break;
      case UNTRUSTED_IDENTITY:      handleUntrustedIdentity(event);                                            break;
    }

    boolean enableVideo = event.getLocalParticipant().getCameraState().getCameraCount() > 0 && enableVideoIfAvailable;

    viewModel.updateFromWebRtcViewModel(event, enableVideo);

    if (enableVideo) {
      enableVideoIfAvailable = false;
      handleSetMuteVideo(false);
    }
  }

  private void handleCallPreJoin(@NonNull WebRtcViewModel event) {
    if (event.getGroupState().isNotIdle()) {
      callScreen.setStatusFromGroupCallState(event.getGroupState());
    }
  }

  private void startCall(boolean isVideoCall) {
    enableVideoIfAvailable = isVideoCall;

    if (isVideoCall) {
      ApplicationDependencies.getSignalCallManager().startOutgoingVideoCall(viewModel.getRecipient().get());
    } else {
      ApplicationDependencies.getSignalCallManager().startOutgoingAudioCall(viewModel.getRecipient().get());
    }

    MessageSender.onMessageSent();
  }

  private final class ControlsListener implements WebRtcCallView.ControlsListener {

    @Override
    public void onStartCall(boolean isVideoCall) {
      viewModel.startCall(isVideoCall);
      //startRecording();
    }

    @Override
    public void onCancelStartCall() {
     // stopRecording();
      finish();
    }

    @Override
    public void onControlsFadeOut() {
      if (videoTooltip != null) {
        videoTooltip.dismiss();
      }
    }

    @Override
    public void showSystemUI() {
      fullscreenHelper.showSystemUI();
    }

    @Override
    public void hideSystemUI() {
      fullscreenHelper.hideSystemUI();
    }

    @Override
    public void onAudioOutputChanged(@NonNull WebRtcAudioOutput audioOutput) {
      switch (audioOutput) {
        case HANDSET:
          handleSetAudioHandset();
          break;
        case HEADSET:
          handleSetAudioBluetooth();
          break;
        case SPEAKER:
          handleSetAudioSpeaker();
          break;
        default:
          throw new IllegalStateException("Unknown output: " + audioOutput);
      }
    }

    @Override
    public void onVideoChanged(boolean isVideoEnabled) {
      handleSetMuteVideo(!isVideoEnabled);
    }

    @Override
    public void onMicChanged(boolean isMicEnabled) {
      handleSetMuteAudio(!isMicEnabled);
    }

    @Override
    public void onCameraDirectionChanged() {
      handleFlipCamera();
    }

    @Override
    public void onEndCallPressed() {
      handleEndCall();
    }

    @Override
    public void onDenyCallPressed() {
      handleDenyCall();
    }

    @Override
    public void onAcceptCallWithVoiceOnlyPressed() {
      handleAnswerWithAudio();
    }

    @Override
    public void onAcceptCallPressed() {
      if (viewModel.isAnswerWithVideoAvailable()) {
        handleAnswerWithVideo();
      } else {
        handleAnswerWithAudio();
      }
    }

    @Override
    public void onShowParticipantsList() {
      CallParticipantsListDialog.show(getSupportFragmentManager());
    }

    @Override
    public void onPageChanged(@NonNull CallParticipantsState.SelectedPage page) {
      viewModel.setIsViewingFocusedParticipant(page);
    }

    @Override
    public void onLocalPictureInPictureClicked() {
      viewModel.onLocalPictureInPictureClicked();
    }
  }


  /*
  * @author Abhishek Kumar Chaubey
  * Block start for recording at client ends
  * */
//  private final SparseIntArray ORIENTATIONS = new SparseIntArray();
//  private final String         RECORD_DIR   = "signal_app";
//  private final int REQUEST_CODE = 1000;
//  private final int DISPLAY_WIDTH = 720;
//  private final int DISPLAY_HEIGHT = 1280;
//
//  private MediaRecorder           mMediaRecorder;
//  private MediaProjection         mMediaProjection;
//  private VirtualDisplay          mVirtualDisplay;
//  private MediaProjectionCallback mMediaProjectionCallback;
//  private String[]                PERMISSIONS  = {
//      Manifest.permission.WRITE_EXTERNAL_STORAGE,
//      Manifest.permission.READ_EXTERNAL_STORAGE,
//      Manifest.permission.RECORD_AUDIO,
//      Manifest.permission.CAMERA,
//      };
//  private MediaProjectionManager  mProjectionManager;
//  private int                     mScreenDensity;
//
//  private List<Integer> occupants;
//
  private void startRecording() {
//    initRecordingComponents();
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
//          //Start Screen Recording
//          initRecorder();
//        }
//    VideoTrack       videoTrack = null;
//    MediaStreamTrack track      = callScreen.call//remoteStream.videoTracks.get(0);
//    if (track instanceof VideoTrack)
//      videoTrack = (VideoTrack) track;
//
//    AudioChannel audioChannel = AudioChannel.OUTPUT;
}
//
//  private void initRecordingComponents() {
//    DisplayMetrics metrics = new DisplayMetrics();
//    WebRtcCallActivity.this.getWindowManager().getDefaultDisplay().getMetrics(metrics);
//    mScreenDensity = metrics.densityDpi;
//    mMediaRecorder = new MediaRecorder();
//
//    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
//      mProjectionManager = (MediaProjectionManager) WebRtcCallActivity.this.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
//    }
//  }
//
//
//  private void initRecorder() {
//
//    String filename = "recording" + "_" + getCurrentTime() + ".mp4";
//    Log.e(TAG, Environment.getExternalStorageDirectory().toString());
//    File mediaStorageDir = new File(
//        Environment
//            .getExternalStorageDirectory(),
//        RECORD_DIR);
//    if (!mediaStorageDir.exists()) {
//      if (!mediaStorageDir.mkdirs()) {
//        Log.d(RECORD_DIR, "Oops! Failed to create "
//                          + RECORD_DIR + " directory");
//
//      }
//    }else{
//      mediaStorageDir.mkdir();
//    }
//    String storage_dir = Environment.getExternalStorageDirectory() + "/" + RECORD_DIR + "/" + filename;
//    try {
//      if (mMediaRecorder != null) {
//        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
//        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
//        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); //THREE_GPP
//        mMediaRecorder.setOutputFile(storage_dir);
//        mMediaRecorder.setVideoSize(DISPLAY_WIDTH, DISPLAY_HEIGHT);
//        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
//        mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
//        mMediaRecorder.setAudioEncodingBitRate(1024 * 1024 * 5);
//        mMediaRecorder.setAudioSamplingRate(16000);
//        mMediaRecorder.setVideoFrameRate(30); // 30
//        mMediaRecorder.setVideoEncodingBitRate(1500 * 1024);
//        int rotation = WebRtcCallActivity.this.getWindowManager().getDefaultDisplay().getRotation();
//        int orientation = ORIENTATIONS.get(rotation + 90);
//        mMediaRecorder.setOrientationHint(orientation);
//        mMediaRecorder.prepare();
//        mMediaRecorder.start();
//      }
//    } catch (IOException e) {
//      e.printStackTrace();
//    }
//  }
//
//  public static String getCurrentTime() {
//    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd_kk-mm-ss");
//    Calendar         c   = Calendar.getInstance();
//    return sdf.format(c.getTime());
//  }
//  public void stopRecording() {
//    if (mMediaRecorder != null) {
//      try {
//
//        mMediaRecorder.stop();
//
//      } catch (IllegalStateException e) {
//        Log.i("Exception", android.util.Log.getStackTraceString(e));
//      } catch (RuntimeException e) {
//        Log.i("Exception", android.util.Log.getStackTraceString(e));
//      } finally {
//        if (mMediaRecorder != null) {
//          mMediaRecorder.release();
//          mMediaRecorder.reset();
//        }
//      }
//
//
//    }
//
//    if (mVirtualDisplay == null) {
//      return;
//    }
//    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
//      mVirtualDisplay.release();
//      destroyMediaProjection();
//    }
//    Log.e(TAG, "Recording stopped");
//  }
//
//  @TargetApi(Build.VERSION_CODES.LOLLIPOP)
//  class MediaProjectionCallback extends MediaProjection.Callback {
//    @Override
//    public void onStop() {
//      if(mMediaRecorder!=null) {
//        mMediaRecorder.stop();
//        mMediaRecorder.reset();
//        mMediaRecorder.release();
//        mMediaProjection = null;
//        stopRecording();
//      }
//    }
//  }
//
//  private void destroyMediaProjection() {
//    if (mMediaProjection != null) {
//      //mMediaProjection = null;
//      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
//        mMediaProjection.unregisterCallback(mMediaProjectionCallback);
//        mMediaProjection.stop();
//        mMediaProjection = null;
//      }
//    }
//    Log.i(TAG, "MediaProjection Stopped");
//  }
//
//  private void startRecordingAfterOnActivityResult(int resultCode, Intent data) {
//    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
//      mMediaProjection = mProjectionManager.getMediaProjection(resultCode, data);
//      mMediaProjectionCallback = new MediaProjectionCallback();
//      mMediaProjection.registerCallback(mMediaProjectionCallback, null);
//      mVirtualDisplay = createVirtualDisplay();
//      mMediaRecorder.start();
//    }
//  }
//  private VirtualDisplay createVirtualDisplay() {
//    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
//      return mMediaProjection.createVirtualDisplay(TAG, DISPLAY_WIDTH, DISPLAY_HEIGHT, mScreenDensity,
//                                                   DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, mMediaRecorder.getSurface(), null, null);
//    } else
//      return null;
//  }


}
