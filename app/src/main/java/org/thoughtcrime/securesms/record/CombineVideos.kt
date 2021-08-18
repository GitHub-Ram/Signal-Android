package org.thoughtcrime.securesms.record

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentActivity
import com.simform.videooperations.CallBackOfQuery
import com.simform.videooperations.Common
import com.simform.videooperations.FFmpegCallBack
import com.simform.videooperations.FFmpegQueryExtension
import com.simform.videooperations.LogMessage
import com.simform.videooperations.Paths
import org.signal.glide.Log
import java.io.File

class CombineVideos {
  val TAG ="CombineVideos"
  val ffmpegQueryExtension = FFmpegQueryExtension()
  var height: Int? = 0
  var width: Int? = 0
  var mediaFiles: List<String>? = null
  var retriever: MediaMetadataRetriever? = null
  lateinit var context : Context

  public fun processStart(context : Context) {
    this.context = context
//    if (mediaFiles != null &&  mediaFiles.isNullOrEmpty()) {
//      val size: Int = mediaFiles!!.size
//      CompletableFuture.runAsync {
//        retriever = MediaMetadataRetriever()
//        retriever?.setDataSource(tvInputPathVideo.text.toString())
//        val bit = retriever?.frameAtTime
//        if (bit != null) {
//          width = bit.width
//          height = bit.height
//        }
//      }
//    } else {
//      Toast.makeText(context,"Video not corrrect", Toast.LENGTH_SHORT).show()
//    }
    combineVideosProcess()
  }

  private fun combineVideosProcess() {
    val outputPath = Common.getFilePath(context, Common.VIDEO)
    val pathsList = ArrayList<Paths>()
    val path1  = Paths()
    path1.filePath = Environment.getExternalStorageDirectory().path + File.separator + "loc" + "videocall.mp4"
    path1.isImageFile = false

    val path2  = Paths()
    path2.filePath = Environment.getExternalStorageDirectory().path + File.separator  + "videocall.mp4"
    path2.isImageFile = false

    pathsList.add(path1)
    pathsList.add(path2)
//    mediaFiles?.let {
//      for (element in it) {
//        val paths = Paths()
//        paths.filePath = element.path
//        paths.isImageFile = false
//        pathsList.add(paths)
//      }

      val query = ffmpegQueryExtension.combineVideos(
        pathsList,
        width,
        height,
        outputPath
      )
      CallBackOfQuery().callQuery( query, object : FFmpegCallBack {
        override fun process(logMessage: LogMessage) {
          Log.i(TAG,logMessage.text)
        }

        override fun success() {
          Log.i(TAG,"Merge  Success")
          processStop()
        }

        override fun cancel() {
          processStop()
        }

        override fun failed() {
          processStop()
        }
      })
    }

  private fun processStop() {
    //TODO
  }
}