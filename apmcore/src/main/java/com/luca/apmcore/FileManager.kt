package com.luca.apmcore

import android.content.Context
import java.io.File
import java.nio.charset.Charset

/**
 * 文件管理工具类
 * <p>
 * 提供文件读写、目录创建等文件操作功能
 */
object FileManager {
    /**
     * 目录类型枚举
     */
    enum class DirectoryType(
        /** 目录类型字符串 */
        val type: String
    ) {
        /** 播放器目录 */
        BytePlayer("BytePlayer"),

        /** 录制目录 */
        AudioRecorder("AudioRecorder")
    }

    /**
     * 写入文本文件
     *
     * @param path 文件路径
     * @param text 文本内容
     */
    fun write(
        path: String,
        text: String
    ) {
        val f = File(path)
        f.writeText(text, Charset.defaultCharset())
    }

    /**
     * 写入字节数组文件
     *
     * @param path  文件路径
     * @param bytes 字节数组
     */
    fun write(
        path: String,
        bytes: ByteArray
    ) {
        val f = File(path)
        f.writeBytes(bytes)
    }

    /**
     * 读取文件为字节数组
     *
     * @param path 文件路径
     * @return 文件内容的字节数组，如果文件不存在则返回 null
     */
    fun read(path: String): ByteArray? {
        val f = File(path)
        if (f.exists()) {
            return f.readBytes()
        }
        return null
    }

    /**
     * 递归删除文件或目录
     *
     * @param path 文件或目录路径
     */
    fun removeRecursively(path: String) {
        val f = File(path)
        if (f.exists()) {
            f.deleteRecursively()
        }
    }

    /**
     * 创建目录
     * <p>
     * 在应用的 files 目录下创建指定路径的目录，如果目录已存在则不创建
     *
     * @param context Android 上下文
     * @param path    相对路径
     * @return 创建的目录的绝对路径
     */
    fun mkdirs(
        context: Context,
        path: String
    ): String {
        val f = File(context.filesDir, path)
        if (!f.exists()) {
            f.mkdirs()
        }
        return f.absolutePath
    }
}