package com.luca.apmcore


import org.webrtc.MediaStreamTrack
import org.webrtc.VideoTrack


import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.ConcurrentLinkedQueue

class ApmManagerSupportRecordThenPlaySelf(private val context: Context) {

    private val TAG = "ApmManager"

    // 目标配置：16k, 16bit, Mono
    private val TARGET_SAMPLE_RATE = 16000

    // 播放器配置
    private val OUT_CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: org.webrtc.AudioTrack? = null

    // 两个 PeerConnection 模拟通话
    private var localPeerConnection: PeerConnection? = null
    private var remotePeerConnection: PeerConnection? = null

    val outputList = ConcurrentLinkedQueue<ByteArray>()

    private var playerAudioTrack: AudioTrack? = null
    private var isPlayerInit = false

    fun initStatus() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        // 配置音频模块
        // 不强制设置 SampleRate，让它跟随系统(48k)，我们在回调里转 16k
        audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseStereoInput(false)
            .setUseStereoOutput(false)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setSamplesReadyCallback { audioSamples ->
                if (audioSamples != null) {
                    val data = audioSamples.data
                    val currentRate = audioSamples.sampleRate

                    if (currentRate == 48000 && TARGET_SAMPLE_RATE == 16000) {
                        // 【降采样逻辑】 48k -> 16k (每3个采样点取1个)
                        // 16bit = 2 bytes per sample.
                        // 48k data: [S1_L, S1_H, S2_L, S2_H, S3_L, S3_H, ...]
                        // We need: [S1_L, S1_H, S4_L, S4_H, ...]
                        val targetSize = data.size / 3
                        // 确保是偶数
                        val finalSize = if (targetSize % 2 == 0) targetSize else targetSize - 1
                        val resampledData = ByteArray(finalSize)

                        var inputIndex = 0
                        var outputIndex = 0
                        while (outputIndex < finalSize - 1 && inputIndex < data.size - 1) {
                            resampledData[outputIndex] = data[inputIndex]     // Low byte
                            resampledData[outputIndex + 1] = data[inputIndex + 1] // High byte
                            outputIndex += 2
                            inputIndex += 6 // 跳过 2个样本 (2 * 2bytes) + 当前样本(2bytes) = 6
                        }
                        outputList.add(resampledData)

                    } else if (currentRate == TARGET_SAMPLE_RATE) {
                        // 已经是 16k，直接拷贝
                        val copyData = ByteArray(data.size)
                        System.arraycopy(data, 0, copyData, 0, data.size)
                        outputList.add(copyData)
                    } else {
                        // 其他采样率，暂时直接输出，或者在这里处理其他逻辑
                        val copyData = ByteArray(data.size)
                        System.arraycopy(data, 0, copyData, 0, data.size)
                        outputList.add(copyData)
                    }
                }
            }
            .createAudioDeviceModule()

        val factoryOptions = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(factoryOptions)
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()

        initPlayer()
    }

    private fun initPlayer() {
        // 播放器始终配置为 16k，接收 outputList 或 putOutsidePcm 的数据
        val minBufferSize = AudioTrack.getMinBufferSize(TARGET_SAMPLE_RATE, OUT_CHANNEL_CONFIG, AUDIO_FORMAT)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(TARGET_SAMPLE_RATE)
            .setEncoding(AUDIO_FORMAT)
            .setChannelMask(OUT_CHANNEL_CONFIG)
            .build()
        playerAudioTrack = AudioTrack(
            attributes, format, minBufferSize, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        isPlayerInit = true
    }

    fun startRecord() {
        if (peerConnectionFactory == null) return

        // 1. 设置系统音频模式
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true

        // 2. 启动 AudioTrack 播放器
        playerAudioTrack?.play()

        // 3. 创建 AudioSource (增加约束)
        val mediaConstraints = MediaConstraints()
        mediaConstraints.mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
        mediaConstraints.mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        mediaConstraints.mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))

        audioSource = peerConnectionFactory!!.createAudioSource(mediaConstraints)
        localAudioTrack = peerConnectionFactory!!.createAudioTrack("ARDAMSa0", audioSource)
        localAudioTrack!!.setEnabled(true)

        // 4. 创建 PeerConnection 配置
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList())
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        // ==================================================
        // 定义 Observer (处理 ICE 交换的关键)
        // ==================================================

        // PC1 的观察者
        val pc1Observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null) {
                    // PC1 发现候选者 -> 给 PC2
                    remotePeerConnection?.addIceCandidate(candidate)
                    Log.d(TAG, "PC1 Found Candidate -> Add to PC2")
                }
            }
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "PC1 IceState: $newState")
            }
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        }

        // PC2 的观察者
        val pc2Observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null) {
                    // PC2 发现候选者 -> 给 PC1
                    localPeerConnection?.addIceCandidate(candidate)
                    Log.d(TAG, "PC2 Found Candidate -> Add to PC1")
                }
            }
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "PC2 IceState: $newState")
            }
            // ... 其他为空
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        }

        // 创建 PC
        localPeerConnection = peerConnectionFactory!!.createPeerConnection(rtcConfig, pc1Observer)
        remotePeerConnection = peerConnectionFactory!!.createPeerConnection(rtcConfig, pc2Observer)

        // PC1 添加 Track
        localPeerConnection?.addTrack(localAudioTrack, listOf("ARDAMS"))

        // ============================
        // 开始 SDP 协商
        // ============================
        localPeerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(offer: SessionDescription?) {
                offer?.let {
                    localPeerConnection?.setLocalDescription(SimpleSdpObserver("PC1 SetLocal"), it)
                    remotePeerConnection?.setRemoteDescription(SimpleSdpObserver("PC2 SetRemote"), it)

                    remotePeerConnection?.createAnswer(object : SdpObserver {
                        override fun onCreateSuccess(answer: SessionDescription?) {
                            answer?.let { ans ->
                                remotePeerConnection?.setLocalDescription(SimpleSdpObserver("PC2 SetLocal"), ans)
                                localPeerConnection?.setRemoteDescription(SimpleSdpObserver("PC1 SetRemote"), ans)
                            }
                        }
                        override fun onSetSuccess() {}
                        override fun onCreateFailure(s: String?) {}
                        override fun onSetFailure(s: String?) {}
                    }, MediaConstraints())
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(s: String?) {}
            override fun onSetFailure(s: String?) {}
        }, mediaConstraints)

        Log.d(TAG, "Start Record: Negotiation started with ICE.")
    }

    fun stopRecord() {
        try {
            playerAudioTrack?.stop()
            playerAudioTrack?.flush()
        } catch (e: Exception) {}

        localPeerConnection?.dispose()
        remotePeerConnection?.dispose()
        localAudioTrack?.dispose()
        audioSource?.dispose()

        localPeerConnection = null
        remotePeerConnection = null
        localAudioTrack = null
        audioSource = null

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_NORMAL

        outputList.clear()
        Log.d(TAG, "Stop Record")
    }

    fun putOutsidePcm(data: ByteArray) {
        if (isPlayerInit && playerAudioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
            playerAudioTrack?.write(data, 0, data.size)
        }
    }

    class SimpleSdpObserver(private val name: String) : SdpObserver {
        override fun onSetSuccess() { Log.d("ApmManager", "$name Success") }
        override fun onSetFailure(s: String?) { Log.e("ApmManager", "$name Failed: $s") }
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onCreateFailure(p0: String?) {}
    }
}