package com.yalantis.ucrop.task

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.exifinterface.media.ExifInterface
import com.yalantis.ucrop.callback.BitmapCropCallback
import com.yalantis.ucrop.model.CropParameters
import com.yalantis.ucrop.model.ExifInfo
import com.yalantis.ucrop.model.ImageState
import com.yalantis.ucrop.util.BitmapLoadUtils
import com.yalantis.ucrop.util.FileUtils
import com.yalantis.ucrop.util.ImageHeaderParser
import kotlinx.coroutines.*
import java.io.*
import java.lang.ref.WeakReference

class BitmapCropTask constructor(
    @NonNull context: Context,
    @Nullable viewBitmap: Bitmap,
    @NonNull imageState: ImageState,
    @NonNull cropParameters: CropParameters,
    @Nullable cropCallback: BitmapCropCallback
) {
    companion object {
        const val TAG = "BitmapCropTask"
    }

    private var mContext: WeakReference<Context>? = null

    private var mViewBitmap: Bitmap? = null

    private var mCropRect: RectF? = null
    private var mCurrentImageRect: RectF? = null

    private var mCurrentScale = 0f
    private var mCurrentAngle: Float = 0f
    private var mMaxResultImageSizeX = 0
    private var mMaxResultImageSizeY: Int = 0

    private var mCompressFormat: CompressFormat? = null
    private var mCompressQuality = 0
    private var mImageInputPath: String? = null
    private var mImageOutputPath: String? = null
    private var mExifInfo: ExifInfo? = null
    private var mCropCallback: BitmapCropCallback

    private var mCroppedImageWidth = 0
    private var mCroppedImageHeight: Int = 0
    private var cropOffsetX = 0
    private var cropOffsetY: Int = 0

    init {
        mContext = WeakReference(context)
        mViewBitmap = viewBitmap
        mCropRect = imageState.cropRect
        mCurrentImageRect = imageState.currentImageRect
        mCurrentScale = imageState.currentScale
        mCurrentAngle = imageState.currentAngle
        mMaxResultImageSizeX = cropParameters.maxResultImageSizeX
        mMaxResultImageSizeY = cropParameters.maxResultImageSizeY
        mCompressFormat = cropParameters.compressFormat
        mCompressQuality = cropParameters.compressQuality
        mImageInputPath = cropParameters.imageInputPath
        mImageOutputPath = cropParameters.imageOutputPath
        mExifInfo = cropParameters.exifInfo
        mCropCallback = cropCallback
    }

    private var job: Job? = null

    fun cancel() = job?.cancel()

    fun execute() {
        job = CoroutineScope(Dispatchers.Default).launch {
            val throwable = doInBackground()
            if (isActive)
                withContext(Dispatchers.Main) {
                    onHandleResult(throwable)
                }
        }
    }

    private fun doInBackground(): Throwable? {
        if (mViewBitmap == null) {
            return NullPointerException("ViewBitmap is null")
        } else if (mViewBitmap!!.isRecycled) {
            return NullPointerException("ViewBitmap is recycled")
        } else if (mCurrentImageRect!!.isEmpty) {
            return NullPointerException("CurrentImageRect is empty")
        }

        try {
            crop()
            mViewBitmap = null
        } catch (throwable: Throwable) {
            return throwable
        }

        return null
    }

    @Throws(IOException::class)
    private fun crop(): Boolean {
        // Downsize if needed
        if (mMaxResultImageSizeX > 0 && mMaxResultImageSizeY > 0) {
            val cropWidth = mCropRect!!.width() / mCurrentScale
            val cropHeight = mCropRect!!.height() / mCurrentScale
            if (cropWidth > mMaxResultImageSizeX || cropHeight > mMaxResultImageSizeY) {
                val scaleX = mMaxResultImageSizeX / cropWidth
                val scaleY: Float = mMaxResultImageSizeY / cropHeight
                val resizeScale = Math.min(scaleX, scaleY)
                val resizedBitmap = Bitmap.createScaledBitmap(
                    mViewBitmap!!,
                    Math.round(mViewBitmap!!.width * resizeScale),
                    Math.round(mViewBitmap!!.height * resizeScale), false
                )
                if (mViewBitmap != resizedBitmap) {
                    mViewBitmap!!.recycle()
                }
                mViewBitmap = resizedBitmap
                mCurrentScale /= resizeScale
            }
        }

        // Rotate if needed
        if (mCurrentAngle != 0f) {
            val tempMatrix = Matrix()
            tempMatrix.setRotate(
                mCurrentAngle,
                (mViewBitmap!!.width / 2).toFloat(),
                (mViewBitmap!!.height / 2).toFloat()
            )
            val rotatedBitmap = Bitmap.createBitmap(
                mViewBitmap!!, 0, 0, mViewBitmap!!.width, mViewBitmap!!.height,
                tempMatrix, true
            )
            if (mViewBitmap != rotatedBitmap) {
                mViewBitmap!!.recycle()
            }
            mViewBitmap = rotatedBitmap
        }

        cropOffsetX = Math.round((mCropRect!!.left - mCurrentImageRect!!.left) / mCurrentScale)
        cropOffsetY = Math.round((mCropRect!!.top - mCurrentImageRect!!.top) / mCurrentScale)
        mCroppedImageWidth = Math.round(mCropRect!!.width() / mCurrentScale)
        mCroppedImageHeight = Math.round(mCropRect!!.height() / mCurrentScale)
        val shouldCrop = shouldCrop(mCroppedImageWidth, mCroppedImageHeight)
        if (shouldCrop) {
            val originalExif = ExifInterface(
                mImageInputPath!!
            )
            saveImage(
                Bitmap.createBitmap(
                    mViewBitmap!!,
                    cropOffsetX,
                    cropOffsetY,
                    mCroppedImageWidth,
                    mCroppedImageHeight
                )
            )
            if (mCompressFormat == CompressFormat.JPEG) {
                ImageHeaderParser.copyExif(
                    originalExif,
                    mCroppedImageWidth,
                    mCroppedImageHeight,
                    mImageOutputPath
                )
            }

            return true
        } else {
            FileUtils.copyFile(mImageInputPath!!, mImageOutputPath!!)
            return false
        }
    }

    @Throws(FileNotFoundException::class)
    private fun saveImage(croppedBitmap: Bitmap) {
        val context = mContext!!.get() ?: return
        var outputStream: OutputStream? = null
        var outStream: ByteArrayOutputStream? = null

        try {
            outputStream = FileOutputStream(File(mImageOutputPath), false)
            outStream = ByteArrayOutputStream()
            croppedBitmap.compress(mCompressFormat, mCompressQuality, outStream)
            outputStream.write(outStream.toByteArray())
            croppedBitmap.recycle()
        } catch (exc: IOException) {
            Log.e(TAG, exc.localizedMessage)
        } finally {
            BitmapLoadUtils.close(outputStream)
            BitmapLoadUtils.close(outStream)
        }
    }

    /**
     * Check whether an image should be cropped at all or just file can be copied to the destination path.
     * For each 1000 pixels there is one pixel of error due to matrix calculations etc.
     *
     * @param width  - crop area width
     * @param height - crop area height
     * @return - true if image must be cropped, false - if original image fits requirements
     */
    private fun shouldCrop(width: Int, height: Int): Boolean {
        var pixelError = 1
        pixelError += Math.round(Math.max(width, height) / 1000f)
        return (mMaxResultImageSizeX > 0 && mMaxResultImageSizeY > 0
                || Math.abs(mCropRect!!.left - mCurrentImageRect!!.left) > pixelError || Math.abs(
            mCropRect!!.top - mCurrentImageRect!!.top
        ) > pixelError || Math.abs(mCropRect!!.bottom - mCurrentImageRect!!.bottom) > pixelError || Math.abs(
            mCropRect!!.right - mCurrentImageRect!!.right
        ) > pixelError || mCurrentAngle != 0f)
    }

    private fun onHandleResult(t: Throwable?) {
        if (t == null) {
            val uri = Uri.fromFile(File(mImageOutputPath))
            mCropCallback.onBitmapCropped(
                uri,
                cropOffsetX,
                cropOffsetY,
                mCroppedImageWidth,
                mCroppedImageHeight
            )
        } else {
            mCropCallback.onCropFailure(t)
        }
    }
}