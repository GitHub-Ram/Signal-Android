package org.thoughtcrime.securesms.record

import VideoHandle.EpEditor
import VideoHandle.EpEditor.OutputOption
import VideoHandle.EpVideo
import VideoHandle.OnEditorListener
import android.os.Environment
import net.ypresto.qtfaststart.QtFastStart
import net.ypresto.qtfaststart.QtFastStart.MalformedFileException
import net.ypresto.qtfaststart.QtFastStart.UnsupportedFileException
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.util.StorageUtil.getVideoPath
import java.io.File
import java.io.IOException


class CombineVideos(val width: Int, val height: Int) {
  val TAG ="CombineVideos"
  fun createScaledFile(delete:Boolean):Array<String>{
    val local = getVideoPath( "scaled_local.mp4")
    val remote = getVideoPath( "scaled_remote.mp4")
    val array  = arrayOf(local, remote)
    val remoteF = File(remote)
    val localF = File(local)

    if(delete){
      try {
//        if (remoteF.exists())
//          remoteF.delete()
//        if (localF.exists())
//          localF.delete()
      } catch (e: IOException) {
        Log.e("TAG", e.toString())
      }
      return array
    }
    try {
      if (!remoteF.exists())
        remoteF.createNewFile()
      if (!localF.exists())
        localF.createNewFile()
    } catch (e: IOException) {
      Log.e("TAG", e.toString())
    }
    return  array
  }



  fun createMoonFile(delete:Boolean):Array<String>{

    val local = getVideoPath( "moon_local.mp4")
    val remote = getVideoPath( "moon_remote.mp4")
    val array  = arrayOf(local, remote)
    val remoteF = File(remote)
    val localF = File(local)
    if(delete){
      try {
//        if (remoteF.exists())
//          remoteF.delete()
//        if (localF.exists())
//          localF.delete()
      } catch (e: IOException) {
        Log.e("TAG", e.toString())
      }
      return array
    }
    try {
      if (!remoteF.exists())
        remoteF.createNewFile()
      if (!localF.exists())
        localF.createNewFile()
    } catch (e: IOException) {
      Log.e("TAG", e.toString())
    }
    return  array
  }

  fun combineVideosProcess(output_file: String,inputAudio:String,inputVideo_local:String,inputVideo_remote:String,temp_video:String) {


    val moonPath = createMoonFile(false)
    try {
      QtFastStart.fastStart(File(inputVideo_local),File( moonPath[0]))
    } catch (m: Exception) {
      Log.e("QT", m.toString())
    }

    try {
      QtFastStart.fastStart(File(inputVideo_remote),File( moonPath[1]))
    } catch (m: Exception) {
      Log.e("QT", m.toString())
    }

    val scaledPath = createScaledFile(false)

    scaleVideo(moonPath[0],scaledPath[0],object : OnEditorListener {
      override fun onSuccess() {
        android.util.Log.d("Progress", "Scaling Success")

        scaleVideo(moonPath[1],scaledPath[1],object : OnEditorListener {
          override fun onSuccess() {
            android.util.Log.d("Progress", "Scaling Success")
            mergeVideo(scaledPath[0],scaledPath[1],output_file)

          }
          override fun onFailure() {
            android.util.Log.d("Progress", "Scaling Failed")
          }
          override fun onProgress(progress: Float) {
            android.util.Log.d("Scaling Progress", "$progress")
          }
        })
      }
      override fun onFailure() {
        android.util.Log.d("Progress", "Scaling Failed")
      }
      override fun onProgress(progress: Float) {
        android.util.Log.d("Scaling Progress", "$progress")
      }
    })
  }


  fun scaleVideo( input:String,output:String, editorListener:OnEditorListener){
    val epVideo = EpVideo(input)
    val outputOption = OutputOption(output)
    outputOption.setWidth(width) //The width and height of the output video, if not set, the original video width and height
    outputOption.setHeight(height) //Output video height
    EpEditor.exec(epVideo, outputOption, editorListener)
  }

  fun mergeVideo(input:String,input2:String,output:String){
   // deleteOldFile(output)
    EpEditor.execCmd("-i $input -i $input2 -filter_complex vstack=inputs=2 $output", 0, object : OnEditorListener {
      override fun onSuccess() {
        Log.d("Progress","Success2")

      }


      override fun onFailure() {
        Log.d("Progress2","Failed2")}
      override fun onProgress(progress: Float) {
        Log.d("Progress2","$progress")}
    })
  }

   fun mergeAudioWithVideo(output_file:String,inputVideo: String,inputAudio:String){
     EpEditor.music(inputVideo, inputAudio, output_file, 0.0f, 1f, object : OnEditorListener{
       override fun onSuccess() {
         android.util.Log.d("Progress", "4Success")
       }

       override fun onFailure() {
         android.util.Log.d("Progress", "4Failed")
       }

       override fun onProgress(progress: Float) {
         android.util.Log.d("Progress", "$progress")
       }
     })
   }

}