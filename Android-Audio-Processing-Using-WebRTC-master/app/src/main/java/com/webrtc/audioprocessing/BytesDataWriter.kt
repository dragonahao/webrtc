package com.webrtc.audioprocessing

import android.content.Context
import java.io.File

class BytesDataWriter {

    public fun write(context: Context, data: ShortArray, frameIndex: Int) {
        val fileName = "${frameIndex}.pcm"
        val f = context.filesDir
        val filePath = f.absolutePath + "/" + fileName
        val outputFile = File(filePath)
        outputFile.writeBytes(toBytes(data))
        println("write file isAllZero=${isAllZero(data)}")
    }

    public fun toBytes(data: ShortArray): ByteArray {
        val bytes = ByteArray(data.size * 2)
        for (i in data.indices) {
            bytes[i * 2] = (data[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (data[i].toInt() shr 8).toByte()
        }
        return bytes
    }

    fun isAllZero(data: ShortArray): Boolean {
        for (i in data.indices) {
            if (data[i] != 0.toShort()) {
                return false
            }
        }
        return true
    }

    fun bytesAlign(data: ByteArray, cellSize: Int = 320): List<ByteArray> {
        // 将不满足320字节的数组填充0
        var remain = data.size % cellSize
        var frameCount = data.size / cellSize
        if (remain != 0) {
            frameCount = data.size + (cellSize - remain)
        } else {
            frameCount =  data.size
        }
        val alignedData = ByteArray(frameCount)
        System.arraycopy(data, 0, alignedData, 0, data.size)
        // 切分数据
        val result = mutableListOf<ByteArray>()
        for (i in 0 until frameCount step cellSize) {
            result.add(alignedData.copyOfRange(i, i + cellSize))
        }
        return result
    }
}