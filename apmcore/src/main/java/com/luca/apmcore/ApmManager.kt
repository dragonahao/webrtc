package com.luca.apmcore

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

open class ApmManager(private val context: Context) {

    public interface ApmManagerCallback {
        fun onAudioFrame(frame: ByteArray)
    }

    private val TAG = "ApmManager"

    // --- 配置区域 ---
    // 外部需要的采样率 (录音输出 和 播放输入 都是 16k)
    private val TARGET_SAMPLE_RATE = 16000

    // 播放器配置 (16k, 16bit, Mono)
    private val OUT_CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    // WebRTC 对象
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: org.webrtc.AudioTrack? = null

    // 两个 PeerConnection 用于“自欺欺人”激活引擎
    private var localPeerConnection: PeerConnection? = null
    private var remotePeerConnection: PeerConnection? = null

    // 录音数据输出队列 (给外部取用)
    //val outputList = ConcurrentLinkedQueue<ByteArray>()

    // 本地播放器 (用于播放外部传入的 PCM)
    private var playerAudioTrack: AudioTrack? = null
    private var isPlayerInit = false
    private var mSaveFilePath = ""
    private var frameCount = 0
    private var mBytesAligner = BytesAligner()
    private var mFileSaveable = false

    private var mCallback: ApmManagerCallback? = null
    fun setCallback(callback: ApmManagerCallback) {
        mCallback = callback
    }

    /**
     * 初始化
     */
    fun initStatus() {
        mSaveFilePath = FileManager.mkdirs(context, FileManager.DirectoryType.AudioRecorder.type)

        // 1. 初始化 Factory
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)


        // 2. 配置 ADM
        // 关键：不强制 setSampleRate，跟随硬件(48k)，在回调里做降采样
        audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setSampleRate(TARGET_SAMPLE_RATE)
            .setInputSampleRate(TARGET_SAMPLE_RATE)
            .setUseStereoInput(false)
            .setUseStereoOutput(false)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setSamplesReadyCallback { audioSamples ->
                if (audioSamples != null) {
                    processAudioCallback(audioSamples.data, audioSamples.sampleRate)
                }
            }
            .createAudioDeviceModule()

        val factoryOptions = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(factoryOptions)
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()

        // 3. 初始化用于播放外部数据的 AudioTrack
        initPlayer()
    }

    /**
     * 处理 WebRTC 回调出来的 PCM 数据
     * 如果是 48k，转成 16k；如果是 16k，直接输出。
     */
    private fun processAudioCallback(data: ByteArray, sampleRate: Int) {
        if (sampleRate == 48000 && TARGET_SAMPLE_RATE == 16000) {
            // 简单降采样算法：每 3 个样本取 1 个
            // 16bit = 2 bytes. 3个样本 = 6 bytes.
            val targetSize = data.size / 3
            // 保证偶数长度
            val finalSize = if (targetSize % 2 == 0) targetSize else targetSize - 1
            val resampledData = ByteArray(finalSize)

            var inputIndex = 0
            var outputIndex = 0
            // 循环直到填满目标或者读完源数据
            while (outputIndex < finalSize && inputIndex + 1 < data.size) {
                resampledData[outputIndex] = data[inputIndex]
                resampledData[outputIndex + 1] = data[inputIndex + 1]
                outputIndex += 2
                inputIndex += 6 // 跳过3个样本
            }
            //  outputList.add(resampledData)
            val alignedData = mBytesAligner.align(resampledData)
            if (mFileSaveable && alignedData != null) {
                FileManager.write(mSaveFilePath + "/${frameCount}.pcm", resampledData)
                frameCount++
            }
            if (alignedData != null) {
                mCallback?.onAudioFrame(alignedData)
            }
        } else {
            // 如果已经是 16k，或者其他情况，直接拷贝
            val copyData = ByteArray(data.size)
            System.arraycopy(data, 0, copyData, 0, data.size)
            // outputList.add(copyData)
            val alignedData = mBytesAligner.align(copyData)
            if (mFileSaveable && alignedData != null) {
                FileManager.write(mSaveFilePath + "/${frameCount}.pcm", copyData)
                frameCount++
            }

            if (alignedData != null) {
                mCallback?.onAudioFrame(alignedData)
            }
        }
    }

    /**
     * 初始化播放器 (Android 原生 AudioTrack)
     */
    private fun initPlayer() {
        val minBufferSize = AudioTrack.getMinBufferSize(TARGET_SAMPLE_RATE, OUT_CHANNEL_CONFIG, AUDIO_FORMAT)

        val attributes = AudioAttributes.Builder()
            // 关键：设为语音通信，这样发出的声音会被 AEC 模块捕捉并消除
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(TARGET_SAMPLE_RATE)
            .setEncoding(AUDIO_FORMAT)
            .setChannelMask(OUT_CHANNEL_CONFIG)
            .build()

        // 缓冲区稍微给大一点，避免断音
        val bufferSize = if (minBufferSize > 0) minBufferSize * 2 else 3200

        playerAudioTrack = AudioTrack(
            attributes,
            format,
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        isPlayerInit = true
        Log.d(TAG, "Player Initialized: 16k Mono")
    }

    /**
     * 开始录音 (启动引擎)
     */
    fun startRecord() {
        if (peerConnectionFactory == null) return

        // 1. 强制切换到通信模式 (开启 AEC 的前提)
        AudioManagerMode.setVoiceCommunication(context)

        // 2. 启动我们的播放器
        try {
            playerAudioTrack?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Player start failed: ${e.message}")
        }

        // 3. WebRTC 源设置 (开启处理算法)
        val mediaConstraints = MediaConstraints()
        mediaConstraints.mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
        mediaConstraints.mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        mediaConstraints.mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        mediaConstraints.mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true")) // 推荐加上 AGC

        audioSource = peerConnectionFactory!!.createAudioSource(mediaConstraints)
        localAudioTrack = peerConnectionFactory!!.createAudioTrack("ARDAMSa0", audioSource)
        localAudioTrack!!.setEnabled(true)

        // 4. 建立 Loopback 连接
        setupLoopbackConnection(mediaConstraints)
    }

    /**
     * 建立 PC1 <-> PC2 连接，激活 ADM
     */
    private fun setupLoopbackConnection(constraints: MediaConstraints) {
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList())
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        // --- Observer 定义 ---
        val pc1Observer = object : BasePeerConnectionObserver() {
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null) remotePeerConnection?.addIceCandidate(candidate)
            }
        }

        val pc2Observer = object : BasePeerConnectionObserver() {
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null) localPeerConnection?.addIceCandidate(candidate)
            }

            // 【关键点】：当 PC2 收到音频轨道时，将其音量设为 0
            // 这样引擎在运行，但我们不会听到自己说话的声音（回声/啸叫）
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = receiver?.track()
                if (track is org.webrtc.AudioTrack) {
                    track.setVolume(0.0) // 静音 WebRTC 的回放
                    Log.d(TAG, "WebRTC Loopback Track Muted (Success)")
                }
            }
        }

        localPeerConnection = peerConnectionFactory!!.createPeerConnection(rtcConfig, pc1Observer)
        remotePeerConnection = peerConnectionFactory!!.createPeerConnection(rtcConfig, pc2Observer)

        // PC1 添加本地麦克风
        localPeerConnection?.addTrack(localAudioTrack, listOf("ARDAMS"))

        // SDP 协商流程 ，  --- 开始握手 ---
        localPeerConnection?.createOffer(object : SimpleSdpObserver("PC1 CreateOffer") {
            override fun onCreateSuccess(offer: SessionDescription?) {
                offer?.let {
                    localPeerConnection?.setLocalDescription(SimpleSdpObserver("PC1 SetLocal"), it)
                    remotePeerConnection?.setRemoteDescription(SimpleSdpObserver("PC2 SetRemote"), it)

                    remotePeerConnection?.createAnswer(object : SimpleSdpObserver("PC2 CreateAnswer") {
                        override fun onCreateSuccess(answer: SessionDescription?) {
                            answer?.let { ans ->
                                remotePeerConnection?.setLocalDescription(SimpleSdpObserver("PC2 SetLocal"), ans)
                                localPeerConnection?.setRemoteDescription(SimpleSdpObserver("PC1 SetRemote"), ans)
                            }
                        }
                    }, constraints)
                }
            }
        }, constraints)

        Log.d(TAG, "Engine starting...")
    }

    /**
     * 停止录音
     */
    fun stopRecord() {
        // 恢复系统音频模式
        AudioManagerMode.resetNormal(context)


        try {
            if (playerAudioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                playerAudioTrack?.stop()
            }
            playerAudioTrack?.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        releasePlayer()

        // 销毁 WebRTC
        localPeerConnection?.dispose()
        remotePeerConnection?.dispose()
        localAudioTrack?.dispose()
        audioSource?.dispose()

        localPeerConnection = null
        remotePeerConnection = null
        localAudioTrack = null
        audioSource = null

        Log.d(TAG, "Stop Record")


    }

    /**
     * 【播放外部音频】
     * 传入的数据必须是 16k, 16bit, Mono PCM
     */
    fun putOutsidePcm(data: ByteArray) {
        if (isPlayerInit && playerAudioTrack != null) {
            if (playerAudioTrack?.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                playerAudioTrack?.play();
            }

            if (playerAudioTrack!!.playState == AudioTrack.PLAYSTATE_PLAYING) {
                // 写入数据到 AudioTrack
                // 由于 Mode 是 IN_COMMUNICATION，这个声音会被硬件 AEC 作为参考信号
                // 从而在麦克风采集时把这部分声音消除掉
                playerAudioTrack!!.write(data, 0, data.size)
            }
        } else {
            // Log.w(TAG, "Player not playing, drop data.")
        }
    }

    /**
     * 统一的播放接口
     */
    fun playAudio(data: ByteArray) {
        putOutsidePcm(data)
    }

    // 清空未播放的音频并停止
    public fun stopPlayerAndClear() {
        if (playerAudioTrack == null) {
            return;
        }
        // 1. 停止播放
        if (playerAudioTrack?.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
            playerAudioTrack?.pause()
        }

//        if (playerAudioTrack?.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
//            playerAudioTrack?.stop();
//        }
        // 2. 清空缓冲区
        playerAudioTrack?.flush();
        // 3. 重置播放位置
        playerAudioTrack?.setPlaybackHeadPosition(0);
        // 4. 可选：释放资源（若不再复用）
        // audioTrack.release();
        // audioTrack = null;
    }

    public fun releasePlayer() {
        playerAudioTrack?.release();
        playerAudioTrack = null;
    }

}


// --- 辅助类 ---
open class BasePeerConnectionObserver : PeerConnection.Observer {
    override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
    override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
    override fun onIceConnectionReceivingChange(p0: Boolean) {}
    override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
    override fun onIceCandidate(p0: IceCandidate?) {}
    override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
    override fun onAddStream(p0: MediaStream?) {}
    override fun onRemoveStream(p0: MediaStream?) {}
    override fun onDataChannel(p0: DataChannel?) {}
    override fun onRenegotiationNeeded() {}
    override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
}

open class SimpleSdpObserver(private val name: String) : SdpObserver {
    override fun onSetSuccess() {}
    override fun onSetFailure(s: String?) {
        Log.e("ApmManager", "$name SetFailure: $s")
    }

    override fun onCreateSuccess(p0: SessionDescription?) {}
    override fun onCreateFailure(p0: String?) {
        Log.e("ApmManager", "$name CreateFailure: $p0")
    }
}