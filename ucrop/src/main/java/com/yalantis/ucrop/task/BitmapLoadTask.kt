package com.yalantis.ucrop.task

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import com.yalantis.ucrop.callback.BitmapLoadCallback
import com.yalantis.ucrop.model.ExifInfo
import com.yalantis.ucrop.util.BitmapLoadUtils
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource
import okio.Sink
import okio.sink
import java.io.*

class BitmapLoadTask constructor(context: Context,
                                 inputUri: Uri, outputUri: Uri?,
                                 requiredWidth: Int, requiredHeight: Int,
                                 loadCallback: BitmapLoadCallback?) {

    companion object {
        const val TAG = "BitmapWorkerTask"
    }

    private val MAX_BITMAP_SIZE = 100 * 1024 * 1024 // 100 MB


    private var mContext: Context? = null
    private var mInputUri: Uri? = null
    private var mOutputUri: Uri? = null
    private var mRequiredWidth = 0
    private var mRequiredHeight = 0

    private var mBitmapLoadCallback: BitmapLoadCallback? = null

    private var job : Job? = null

    class BitmapWorkerResult {
        var mBitmapResult: Bitmap? = null
        var mExifInfo: ExifInfo? = null
        var mBitmapWorkerException: Exception? = null

        constructor(bitmapResult: Bitmap, exifInfo: ExifInfo) {
            mBitmapResult = bitmapResult
            mExifInfo = exifInfo
        }

        constructor(bitmapWorkerException: Exception) {
            mBitmapWorkerException = bitmapWorkerException
        }
    }

    init {
        mContext = context
        mInputUri = inputUri
        mOutputUri = outputUri
        mRequiredWidth = requiredWidth
        mRequiredHeight = requiredHeight
        mBitmapLoadCallback = loadCallback
    }

    fun execute() {
        job = CoroutineScope(Dispatchers.Default).launch {
            val result = doInBackground()
            if(isActive)
            withContext(Dispatchers.Main) {
                onPostExecute(result)
            }
        }
    }

    fun cancel() = job?.cancel()

    private fun doInBackground(): BitmapWorkerResult {
        if (mInputUri == null) {
            return BitmapWorkerResult(NullPointerException("Input Uri cannot be null"))
        }
        try {
            processInputUri()
        } catch (e: NullPointerException) {
            return BitmapWorkerResult(e)
        } catch (e: IOException) {
            return BitmapWorkerResult(e)
        }
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        options.inSampleSize = BitmapLoadUtils.calculateInSampleSize(options, mRequiredWidth, mRequiredHeight)
        options.inJustDecodeBounds = false
        var decodeSampledBitmap: Bitmap? = null
        var decodeAttemptSuccess = false
        while (!decodeAttemptSuccess) {
            try {
                val stream = mContext!!.contentResolver.openInputStream(mInputUri!!)
                try {
                    decodeSampledBitmap = BitmapFactory.decodeStream(stream, null, options)
                    if (options.outWidth == -1 || options.outHeight == -1) {
                        return BitmapWorkerResult(IllegalArgumentException("Bounds for bitmap could not be retrieved from the Uri: [$mInputUri]"))
                    }
                } finally {
                    BitmapLoadUtils.close(stream)
                }
                if (checkSize(decodeSampledBitmap, options)) continue
                decodeAttemptSuccess = true
            } catch (error: OutOfMemoryError) {
                Log.e(TAG, "doInBackground: BitmapFactory.decodeFileDescriptor: ", error)
                options.inSampleSize *= 2
            } catch (e: IOException) {
                Log.e(TAG, "doInBackground: ImageDecoder.createSource: ", e)
                return BitmapWorkerResult(IllegalArgumentException("Bitmap could not be decoded from the Uri: [$mInputUri]", e))
            }
        }
        if (decodeSampledBitmap == null) {
            return BitmapWorkerResult(IllegalArgumentException("Bitmap could not be decoded from the Uri: [$mInputUri]"))
        }
        val exifOrientation = BitmapLoadUtils.getExifOrientation(mContext!!, mInputUri!!)
        val exifDegrees = BitmapLoadUtils.exifToDegrees(exifOrientation)
        val exifTranslation = BitmapLoadUtils.exifToTranslation(exifOrientation)
        val exifInfo = ExifInfo(exifOrientation, exifDegrees, exifTranslation)
        val matrix = Matrix()
        if (exifDegrees != 0) {
            matrix.preRotate(exifDegrees.toFloat())
        }
        if (exifTranslation != 1) {
            matrix.postScale(exifTranslation.toFloat(), 1f)
        }
        return if (!matrix.isIdentity) {
            BitmapWorkerResult(BitmapLoadUtils.transformBitmap(decodeSampledBitmap, matrix), exifInfo)
        } else BitmapWorkerResult(decodeSampledBitmap, exifInfo)
    }

    @Throws(NullPointerException::class, IOException::class)
    private fun processInputUri() {
        val inputUriScheme = mInputUri!!.scheme
        Log.d(TAG, "Uri scheme: $inputUriScheme")
        if ("http" == inputUriScheme || "https" == inputUriScheme) {
            try {
                downloadFile(mInputUri!!, mOutputUri)
            } catch (e: NullPointerException) {
                Log.e(TAG, "Downloading failed", e)
                throw e
            } catch (e: IOException) {
                Log.e(TAG, "Downloading failed", e)
                throw e
            }
        } else if ("content" == inputUriScheme) {
            try {
                copyFile(mInputUri!!, mOutputUri)
            } catch (e: NullPointerException) {
                Log.e(TAG, "Copying failed", e)
                throw e
            } catch (e: IOException) {
                Log.e(TAG, "Copying failed", e)
                throw e
            }
        } else if ("file" != inputUriScheme) {
            Log.e(TAG, "Invalid Uri scheme $inputUriScheme")
            throw IllegalArgumentException("Invalid Uri scheme$inputUriScheme")
        }
    }

    @Throws(NullPointerException::class, IOException::class)
    private fun copyFile(inputUri: Uri, outputUri: Uri?) {
        Log.d(TAG, "copyFile")
        if (outputUri == null) {
            throw NullPointerException("Output Uri is null - cannot copy image")
        }
        var inputStream: InputStream? = null
        var outputStream: OutputStream? = null
        try {
            inputStream = mContext!!.contentResolver.openInputStream(inputUri)
            outputStream = FileOutputStream(File(outputUri.path))
            if (inputStream == null) {
                throw NullPointerException("InputStream for given input Uri is null")
            }
            val buffer = ByteArray(1024)
            var length: Int
            while (inputStream.read(buffer).also { length = it } > 0) {
                outputStream.write(buffer, 0, length)
            }
        } finally {
            BitmapLoadUtils.close(outputStream)
            BitmapLoadUtils.close(inputStream)

            // swap uris, because input image was copied to the output destination
            // (cropped image will override it later)
            mInputUri = mOutputUri
        }
    }

    @Throws(NullPointerException::class, IOException::class)
    private fun downloadFile(inputUri: Uri, outputUri: Uri?) {
        Log.d(TAG, "downloadFile")
        if (outputUri == null) {
            throw NullPointerException("Output Uri is null - cannot download image")
        }
        val client = OkHttpClient()
        var source: BufferedSource? = null
        var sink: Sink? = null
        var response: Response? = null
        try {
            val request = Request.Builder()
                    .url(inputUri.toString())
                    .build()
            response = client.newCall(request).execute()
            source = response.body!!.source()
            val outputStream = mContext!!.contentResolver.openOutputStream(outputUri)
            if (outputStream != null) {
                sink = outputStream.sink()
                source.readAll(sink)
            } else {
                throw NullPointerException("OutputStream for given output Uri is null")
            }
        } finally {
            BitmapLoadUtils.close(source)
            BitmapLoadUtils.close(sink)
            if (response != null) {
                BitmapLoadUtils.close(response.body)
            }
            client.dispatcher.cancelAll()

            // swap uris, because input image was downloaded to the output destination
            // (cropped image will override it later)
            mInputUri = mOutputUri
        }
    }

    private fun onPostExecute(result: BitmapWorkerResult) {
        if (result.mBitmapWorkerException == null) {
            mBitmapLoadCallback!!.onBitmapLoaded(result.mBitmapResult!!, result.mExifInfo!!, mInputUri!!.path!!,
                    mOutputUri?.path)
        } else {
            mBitmapLoadCallback!!.onFailure(result.mBitmapWorkerException!!)
        }
    }

    private fun checkSize(bitmap: Bitmap?, options: BitmapFactory.Options): Boolean {
        val bitmapSize = bitmap?.byteCount ?: 0
        if (bitmapSize > MAX_BITMAP_SIZE) {
            options.inSampleSize *= 2
            return true
        }
        return false
    }
}