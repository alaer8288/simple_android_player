package com.example.simpleplayer

import android.media.MediaCodecList

/**
 * 解码器选择器。
 *
 * 背景说明：Codec2 是 Android 的解码器 HAL（硬件抽象层），应用无法直接调用。
 * 应用层通过 MediaCodec 访问解码器，Android 10+ 平台会把请求路由到厂商的
 * Codec2 硬件组件（名字以 "c2." 开头，如 c2.qti.avc.decoder / c2.mtk.avc.decoder）。
 *
 * 默认的 createDecoderByType() 可能选中软解（c2.android.* / OMX.google.*），
 * 所以这里手动枚举解码器并筛选：
 *   优先级 1：厂商 Codec2 硬解（c2.* 且非 c2.android / c2.omx）
 *   优先级 2：厂商 OMX 硬解（OMX.* 且非 OMX.google，旧设备兜底）
 *   都没有则返回 null，由调用方回退到系统默认解码器。
 */
object CodecSelector {

    /** AOSP 自带软解的前缀 */
    private val SOFTWARE_PREFIXES = arrayOf(
        "OMX.google.",  // Google 软解（OMX 时代）
        "c2.android.",  // Google 软解（Codec2 时代）
        "c2.omx.",      // OMX 兼容包装层，非原生 C2 硬件组件
        "OMX.ffmpeg."
    )

    data class DecoderChoice(val name: String, val isCodec2: Boolean)

    fun findVideoDecoder(mime: String): DecoderChoice? {
        val c2Names = ArrayList<String>(4)
        val omxNames = ArrayList<String>(4)

        val infos = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        } catch (e: Exception) {
            return null
        }

        for (info in infos) {
            if (info.isEncoder) continue
            val name = info.name ?: continue
            if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
            if (SOFTWARE_PREFIXES.any { name.startsWith(it, ignoreCase = true) }) continue

            val lower = name.lowercase()
            when {
                lower.startsWith("c2.") -> c2Names.add(name)
                lower.startsWith("omx.") -> omxNames.add(name)
            }
        }

        c2Names.firstOrNull()?.let { return DecoderChoice(it, true) }
        omxNames.firstOrNull()?.let { return DecoderChoice(it, false) }
        return null
    }

    /** 判断某个解码器名字属于 Codec2 硬解 / OMX 硬解 / 软解，用于界面展示 */
    fun describeCodec(name: String): String = when {
        name.startsWith("c2.android.", true) -> "软解（c2.android）"
        name.startsWith("c2.omx.", true) -> "软解（c2.omx 包装）"
        name.startsWith("c2.", true) -> "Codec2 硬解"
        name.startsWith("OMX.google.", true) -> "软解（OMX.google）"
        name.startsWith("OMX.", true) -> "硬解（OMX 路径）"
        else -> "其他"
    }

    /** 输出本机全部解码器清单，用于 logcat 验证（tag: SimplePlayer） */
    fun dumpAllDecoders(): String {
        val sb = StringBuilder("本机解码器列表（REGULAR_CODECS）：\n")
        try {
            val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            for (info in infos) {
                if (info.isEncoder) continue
                sb.append("  ")
                    .append(info.name)
                    .append("  [").append(describeCodec(info.name ?: "?")).append("]")
                    .append('\n')
            }
        } catch (e: Exception) {
            sb.append("  枚举失败: ").append(e.message)
        }
        return sb.toString()
    }
}
