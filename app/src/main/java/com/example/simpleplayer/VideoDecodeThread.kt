package com.example.simpleplayer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.io.IOException

/**
 * 最小视频解码线程：MediaExtractor 解封装 -> MediaCodec 硬解 -> 渲染到 Surface。
 * 不处理音频。到 EOS 时若 looping == true 则 seek 回开头继续解码（无限循环）。
 *
 * 所有回调都投递到主线程执行，activity 侧可放心更新 UI。
 */
class VideoDecodeThread(
    private val context: Context,
    private val uri: Uri,
    private val surface: Surface
) : Thread("VideoDecodeThread") {

    interface Callbacks {
        fun onDecoderSelected(codecName: String) {}
        fun onVideoSizeChanged(width: Int, height: Int) {}
        fun onPlaybackEnded() {}
        fun onPlaybackStopped() {}
        fun onError(message: String) {}
    }

    @Volatile
    var looping = true

    @Volatile
    private var stopRequested = false

    var callbacks: Callbacks? = null

    private val main = Handler(Looper.getMainLooper())

    fun requestStop() {
        stopRequested = true
        interrupt() // 让阻塞在 dequeue* 超时等待中的循环尽快退出
    }

    override fun run() {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var endedNaturally = false
        try {
            // 1. 解封装，找到视频轨道（失败时附带诊断信息，便于定位权限/路径/格式问题）
            extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
            } catch (e: IOException) {
                throw IOException("${e.message}；${describeSource(uri)}", e)
            }
            val trackIndex = selectVideoTrack(extractor)
            if (trackIndex < 0) {
                postError("文件里没有视频轨道")
                return
            }
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime == null) {
                postError("无法读取视频编码格式")
                return
            }

            // 2. 优先选厂商 Codec2 硬解，失败则回退系统默认
            val choice = CodecSelector.findVideoDecoder(mime)
            codec = if (choice != null) {
                try {
                    MediaCodec.createByCodecName(choice.name)
                } catch (e: IOException) {
                    Log.w(TAG, "打开 ${choice.name} 失败，回退系统默认: ${e.message}")
                    MediaCodec.createDecoderByType(mime)
                }
            } else {
                MediaCodec.createDecoderByType(mime)
            }
            val codecName = codec.name
            Log.i(TAG, "实际使用解码器: $codecName (${CodecSelector.describeCodec(codecName)})")
            main.post { callbacks?.onDecoderSelected(codecName) }

            // 3. 配置并启动（format 里带 csd-0/csd-1 等解码必需参数）
            codec.configure(format, surface, null, 0)
            codec.start()
            postInitialVideoSize(format)

            // 4. 解码主循环
            // timestampOffsetUs：循环回绕时给时间戳累加一个偏移，保证送给解码器的
            // 时间戳始终单调递增。若直接从 0 重来，部分设备的渲染调度会把接缝处的
            // 帧当成"过去帧"处理，导致每次循环开头出现可感知的卡顿。
            val info = BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false
            var timestampOffsetUs = 0L
            var lastSampleTimeUs = 0L
            while (!sawOutputEOS && !stopRequested) {
                if (!sawInputEOS) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)
                        if (buffer == null) {
                            postError("getInputBuffer 返回 null")
                            return
                        }
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEOS = true
                        } else {
                            val sampleTime = extractor.sampleTime
                            codec.queueInputBuffer(
                                inIndex, 0, sampleSize, sampleTime + timestampOffsetUs, 0
                            )
                            lastSampleTimeUs = sampleTime
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = codec.outputFormat
                        val w = outFormat.getInteger(MediaFormat.KEY_WIDTH)
                        val h = outFormat.getInteger(MediaFormat.KEY_HEIGHT)
                        main.post { callbacks?.onVideoSizeChanged(w, h) }
                    }

                    outIndex >= 0 -> {
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        // 任何帧都不丢弃：正常帧按时间戳渲染，EOS 帧也渲染出来
                        codec.releaseOutputBuffer(outIndex, true)
                        if (eos && looping && !stopRequested) {
                            // 循环：渲染完最后一帧后 seek 回开头并 flush 解码器继续
                            timestampOffsetUs += lastSampleTimeUs + LOOP_GAP_US
                            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                            codec.flush()
                            sawInputEOS = false
                        } else if (eos) {
                            sawOutputEOS = true
                            endedNaturally = true
                        }
                    }
                    // INFO_TRY_AGAIN_LATER：继续循环
                }
            }
        } catch (e: Exception) {
            if (!stopRequested) {
                Log.e(TAG, "播放出错", e)
                postError("播放出错: ${e.javaClass.simpleName}: ${e.message}")
            }
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
            if (endedNaturally) {
                main.post { callbacks?.onPlaybackEnded() }
            } else {
                main.post { callbacks?.onPlaybackStopped() }
            }
        }
    }

    /** 按轨道格式上报初始分辨率（90/270 度旋转视频交换宽高显示） */
    private fun postInitialVideoSize(format: MediaFormat) {
        var w = format.getInteger(MediaFormat.KEY_WIDTH)
        var h = format.getInteger(MediaFormat.KEY_HEIGHT)
        if (format.containsKey(MediaFormat.KEY_ROTATION)) {
            val rotation = format.getInteger(MediaFormat.KEY_ROTATION)
            if (rotation == 90 || rotation == 270) {
                val t = w; w = h; h = t
            }
        }
        main.post { callbacks?.onVideoSizeChanged(w, h) }
    }

    private fun selectVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i
        }
        return -1
    }

    /** 打不开数据源时，从应用进程视角诊断该文件的状态 */
    private fun describeSource(uri: Uri): String {
        if (uri.scheme == "file" || uri.scheme == null) {
            val f = java.io.File(uri.path ?: uri.toString())
            return when {
                !f.exists() -> "诊断：文件不存在（$f），请核对路径"
                !f.canRead() -> "诊断：文件存在但无权读取（$f），请授予存储读权限"
                else -> "诊断：文件可读，大小 ${f.length()} 字节（$f），" +
                    "但无容器被识别——裸 .h264/.h265 ES 流不受支持"
            }
        }
        return "诊断：content 源 $uri 无法打开，可能缺少授权"
    }

    private fun postError(message: String) {
        Log.e(TAG, message)
        main.post { callbacks?.onError(message) }
    }

    companion object {
        private const val TAG = "SimplePlayer"
        private const val TIMEOUT_US = 10_000L
        private const val LOOP_GAP_US = 40_000L // 循环接缝处的时间戳间隔
    }
}
