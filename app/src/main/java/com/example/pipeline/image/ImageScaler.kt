package com.example.pipeline.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

object ImageScaler {

    suspend fun loadAndCacheOriginal(context: Context, uri: Uri, targetFile: File): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_DEFAULT
                    decoder.isMutableRequired = true
                }
            } else {
                val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable = true
                }
                val bmp = BitmapFactory.decodeStream(inputStream, null, options)
                inputStream.close()
                bmp
            }

            if (bitmap != null) {
                targetFile.parentFile?.mkdirs()
                FileOutputStream(targetFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.flush()
                }
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    suspend fun createWorkingCopy(originalBitmap: Bitmap, targetFile: File, maxLongEdge: Int = 2048): Bitmap = withContext(Dispatchers.IO) {
        val width = originalBitmap.width
        val height = originalBitmap.height
        val longEdge = max(width, height)

        val scaledBitmap = if (longEdge > maxLongEdge) {
            val scale = maxLongEdge.toFloat() / longEdge
            val matrix = Matrix().apply { postScale(scale, scale) }
            Bitmap.createBitmap(originalBitmap, 0, 0, width, height, matrix, true)
        } else {
            originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
        }

        targetFile.parentFile?.mkdirs()
        FileOutputStream(targetFile).use { out ->
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.flush()
        }

        scaledBitmap
    }

    suspend fun bitmapToBase64(bitmap: Bitmap, format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG, quality: Int = 85): String = withContext(Dispatchers.Default) {
        val stream = ByteArrayOutputStream()
        bitmap.compress(format, quality, stream)
        val byteArray = stream.toByteArray()
        Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    suspend fun saveBitmapPng(bitmap: Bitmap, file: File): Boolean = withContext(Dispatchers.IO) {
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun loadBitmapFromFile(file: File): Bitmap? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(file)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_DEFAULT
                    decoder.isMutableRequired = true
                }
            } else {
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable = true
                }
                BitmapFactory.decodeFile(file.absolutePath, options)
            }
        } catch (e: Exception) {
            null
        }
    }
}
