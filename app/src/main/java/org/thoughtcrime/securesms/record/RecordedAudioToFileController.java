package org.thoughtcrime.securesms.record;

import android.media.AudioFormat;
import android.os.Environment;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import com.cachy.webrtc.audio.JavaAudioDeviceModule;
import com.cachy.webrtc.audio.JavaAudioDeviceModule.SamplesReadyCallback;
/**
 * Implements the AudioRecordSamplesReadyCallback interface and writes
 * recorded raw audio samples to an output file.
 */
public class RecordedAudioToFileController implements SamplesReadyCallback {
  private static final String TAG = "RecordedAudioToFile";
  private String fileName;
  private static final long MAX_FILE_SIZE_IN_BYTES = 58348800L;
  private final Object lock = new Object();
  private final     ExecutorService executor;
  @Nullable private OutputStream    rawAudioFileOutputStream;
  private           boolean         isRunning;
  private int RECORDER_SAMPLERATE;
  private int channel  = 1;
  private long fileSizeInBytes;
  public RecordedAudioToFileController(ExecutorService executor) {
    Log.d(TAG, "ctor");
    this.executor = executor;
  }
  /**
   * Should be called on the same executor thread as the one provided at
   * construction.
   */
  public boolean start() {
    Log.d(TAG, "start");
    if (!isExternalStorageWritable()) {
      Log.e(TAG, "Writing to external media is not possible");
      return false;
    }
    synchronized (lock) {
      isRunning = true;
    }
    return true;
  }
  /**
   * Should be called on the same executor thread as the one provided at
   * construction.
   */
  public void stop() {
    Log.d(TAG, "stop");
    synchronized (lock) {
      isRunning = false;
      if (rawAudioFileOutputStream != null) {
        try {
          rawAudioFileOutputStream.close();
        } catch (IOException e) {
          Log.e(TAG, "Failed to close file with saved input audio: " + e);
        }
        try {
          rawToWave(openRawAudioFile(),openAudioOutputFile());
        } catch (IOException e) {
          e.printStackTrace();
        }
        rawAudioFileOutputStream = null;

      }
      fileSizeInBytes = 0;
    }
  }
  // Checks if external storage is available for read and write.
  private boolean isExternalStorageWritable() {
    String state = Environment.getExternalStorageState();
    if (Environment.MEDIA_MOUNTED.equals(state)) {
      return true;
    }
    return false;
  }
  // Utilizes audio parameters to create a file name which contains sufficient
  // information so that the file can be played using an external file player.
  // Example: /sdcard/recorded_audio_16bits_48000Hz_mono.pcm.
  private void openRawAudioOutputFile(int sampleRate, int channelCount) {
    RECORDER_SAMPLERATE = sampleRate;
    this.channel = channelCount;
    final String fileName = Environment.getExternalStorageDirectory().getPath() + File.separator
                            + "recorded_audio_16bits_" + String.valueOf(sampleRate) + "Hz"
                            + ((channelCount == 1) ? "_mono" : "_stereo") + ".pcm";
    this.fileName = fileName;
    final File outputFile = new File(fileName);
    try {
      rawAudioFileOutputStream = new FileOutputStream(outputFile);
    } catch (FileNotFoundException e) {
      Log.e(TAG, "Failed to open audio output file: " + e.getMessage());
    }
    Log.d(TAG, "Opened file for recording: " + fileName);
  }

  private File openRawAudioFile() {

    final File outputFile = new File(fileName);

    Log.d(TAG, "file for convert recording: " + fileName);
    return  outputFile;
  }

  private File openAudioOutputFile() {
    final String fileName = Environment.getExternalStorageDirectory().getPath() + File.separator
                            + "recorded_audio" + ".wav";
    File audioFile = new File(fileName);
    Log.d(TAG, "file for wav recording: " + fileName);
    return audioFile;
  }
  // Called when new audio samples are ready.
  @Override
  public void onWebRtcAudioRecordSamplesReady(JavaAudioDeviceModule.AudioSamples samples) {
    // The native audio layer on Android should use 16-bit PCM format.
    if (samples.getAudioFormat() != AudioFormat.ENCODING_PCM_16BIT) {
      Log.e(TAG, "Invalid audio format");
      return;
    }
    synchronized (lock) {
      // Abort early if stop() has been called.
      if (!isRunning) {
        return;
      }
      // Open a new file for the first callback only since it allows us to add audio parameters to
      // the file name.
      if (rawAudioFileOutputStream == null) {
        openRawAudioOutputFile(samples.getSampleRate(), samples.getChannelCount());
        fileSizeInBytes = 0;
      }
    }
    // Append the recorded 16-bit audio samples to the open output file.
    executor.execute(() -> {
      if (rawAudioFileOutputStream != null) {
        try {
          // Set a limit on max file size. 58348800 bytes corresponds to
          // approximately 10 minutes of recording in mono at 48kHz.
          if (fileSizeInBytes < MAX_FILE_SIZE_IN_BYTES) {
            // Writes samples.getData().length bytes to output stream.
            rawAudioFileOutputStream.write(samples.getData());
            fileSizeInBytes += samples.getData().length;
          }
        } catch (IOException e) {
          Log.e(TAG, "Failed to write audio to file: " + e.getMessage());
        }
      }
    });
  }

  private void rawToWave(final File rawFile, final File waveFile) throws IOException {

    byte[]          rawData = new byte[(int) rawFile.length()];
    DataInputStream input   = null;
    try {
      input = new DataInputStream(new FileInputStream(rawFile));
      input.read(rawData);
    } finally {
      if (input != null) {
        input.close();
      }
    }

    DataOutputStream output = null;
    try {
      output = new DataOutputStream(new FileOutputStream(waveFile));
      // WAVE header
      // see http://ccrma.stanford.edu/courses/422/projects/WaveFormat/
      writeString(output, "RIFF"); // chunk id
      writeInt(output, 36 + rawData.length); // chunk size
      writeString(output, "WAVE"); // format
      writeString(output, "fmt "); // subchunk 1 id
      writeInt(output, 16); // subchunk 1 size
      writeShort(output, (short) 1); // audio format (1 = PCM)
      writeShort(output, (short) this.channel); // number of channels
      writeInt(output, RECORDER_SAMPLERATE); // sample rate
      writeInt(output, RECORDER_SAMPLERATE * 2); // byte rate
      writeShort(output, (short) 2); // block align
      writeShort(output, (short) 16); // bits per sample
      writeString(output, "data"); // subchunk 2 id
      writeInt(output, rawData.length); // subchunk 2 size
      // Audio data (conversion big endian -> little endian)
      short[] shorts = new short[rawData.length / 2];
      ByteBuffer.wrap(rawData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts);
      ByteBuffer bytes = ByteBuffer.allocate(shorts.length * 2);
      for (short s : shorts) {
        bytes.putShort(s);
      }

      output.write(fullyReadFileToBytes(rawFile));
    } finally {
      if (output != null) {
        output.close();
      }
    }
  }
  byte[] fullyReadFileToBytes(File f) throws IOException {
    int size = (int) f.length();
    byte bytes[] = new byte[size];
    byte tmpBuff[] = new byte[size];
    FileInputStream fis= new FileInputStream(f);
    try {

      int read = fis.read(bytes, 0, size);
      if (read < size) {
        int remain = size - read;
        while (remain > 0) {
          read = fis.read(tmpBuff, 0, remain);
          System.arraycopy(tmpBuff, 0, bytes, size - remain, read);
          remain -= read;
        }
      }
    }  catch (IOException e){
      throw e;
    } finally {
      fis.close();
    }

    return bytes;
  }
  private void writeInt(final DataOutputStream output, final int value) throws IOException {
    output.write(value >> 0);
    output.write(value >> 8);
    output.write(value >> 16);
    output.write(value >> 24);
  }

  private void writeShort(final DataOutputStream output, final short value) throws IOException {
    output.write(value >> 0);
    output.write(value >> 8);
  }

  private void writeString(final DataOutputStream output, final String value) throws IOException {
    for (int i = 0; i < value.length(); i++) {
      output.write(value.charAt(i));
    }
  }
}
