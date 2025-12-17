package com.webrtc.audioprocessing

import android.content.Context
import android.util.Log

// 外部pcm生成器
object PcmGenerator {
    /**
     * 从 assets 加载 PCM 文件
     */
    fun loadPcmShort(context: Context, fileName: String): ShortArray {
        return try {
            context.assets.open(fileName).use { inputStream ->
                val byteArray = inputStream.readBytes()
                // 将 byte[] 转换为 short[]
                val shortArray = ShortArray(byteArray.size / 2)
                for (i in shortArray.indices) {
                    val low = byteArray[i * 2].toInt() and 0xFF
                    val high = byteArray[i * 2 + 1].toInt() and 0xFF
                    shortArray[i] = ((high shl 8) or low).toShort()
                }
                Log.i("PcmGenerator", "Loaded PCM file: $fileName, samples: ${shortArray.size}")
                shortArray
            }
        } catch (e: Exception) {
            Log.e("PcmGenerator", "Failed to load PCM file: $fileName", e)
            ShortArray(0)
        }
    }

    fun loadPcmBytes(context: Context, fileName: String): ByteArray {
        return try {
            context.assets.open(fileName).use { inputStream ->
                val byteArray = inputStream.readBytes()
                Log.i("PcmGenerator", "Loaded PCM file: $fileName, samples: ${byteArray.size}")
                byteArray
            }
        } catch (e: Exception) {
            Log.e("PcmGenerator", "Failed to load PCM file: $fileName", e)
            ByteArray(0)
        }
    }
}