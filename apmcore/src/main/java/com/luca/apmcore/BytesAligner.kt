package com.luca.apmcore

class BytesAligner {
    // 对齐为至少为大于2048且为320的整数倍字节的整数倍
    private val mBytes = ArrayList<Byte>()
    private val minAlignSize = 2048

    @Synchronized
    fun align(bytes: ByteArray): ByteArray? {
        mBytes.addAll(bytes.toList())
        if (mBytes.size > minAlignSize) {
            // 取出2048字节
            val newBytes = mBytes.subList(0, minAlignSize).toByteArray()
            mBytes.subList(0, minAlignSize).clear()
            return newBytes
        }
        return null
    }
}