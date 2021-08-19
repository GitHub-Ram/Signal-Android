package org.thoughtcrime.securesms.record

import VideoHandle.EpEditor
import VideoHandle.OnEditorListener

class CombineVideos {
  val TAG ="CombineVideos"

/*
* Combine videos with input
* */
   fun combineVideosProcess(output_file: String,inputAudio:String,inputVideo_local:String,inputVideo_remote:String,temp_video:String) {
/*
*Filter videos
* */
     EpEditor.execCmd(
       "-i $inputVideo_local -i $inputVideo_remote -filter_complex hstack $temp_video",
       0,
       object : OnEditorListener {
         override fun onSuccess() {
           android.util.Log.d("Progress", "Success")
           mergeAudioWithVideo(output_file,temp_video,inputAudio);

         }
         override fun onFailure() {
           android.util.Log.d("Progress", "Failed")
         }

         override fun onProgress(progress: Float) {
           android.util.Log.d("Progress", "$progress")
         }
       }
     )


   }
  /*
  * Merge Audio with generated temp_video obtained side by side
  * */
   fun mergeAudioWithVideo(output_file:String,inputVideo: String,inputAudio:String){
     EpEditor.music(inputVideo, inputAudio, output_file, 0.0f, 1f, object : OnEditorListener{
       override fun onSuccess() {
         android.util.Log.d("Progress", "Success")
       }

       override fun onFailure() {
         android.util.Log.d("Progress", "Failed")
       }

       override fun onProgress(progress: Float) {
         android.util.Log.d("Progress", "$progress")
       }
     })
   }

}