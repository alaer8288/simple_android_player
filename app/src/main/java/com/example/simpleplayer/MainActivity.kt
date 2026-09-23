package com.example.simpleplayer

import android.app.Activity
import android.content.Intent
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.min

/**
 * 最简视频播放器主界面：
 *  - ACTION_OPEN_DOCUMENT 选视频（无需存储权限），或"内置示例"直接播放
 *  - TextureView 显示（旋转/缩放时 SurfaceTexture 不会销毁，播放不中断）
 *  - 循环开关实时生效
 *  - 底部显示实际使用的解码器，方便确认是否走了硬解
 *  - 支持命令行指定视频路径：am start --es videoPath /sdcard/xxx.mp4
 */
class MainActivity : Activity(), VideoDecodeThread.Callbacks, TextureView.SurfaceTextureListener {

    private lateinit var textureView: TextureView
    private lateinit var codecInfoText: TextView
    private lateinit var loopSwitch: Switch

    private var videoUri: Uri? = null
    private var decodeThread: VideoDecodeThread? = null

    /** SurfaceTexture 尚未就绪时收到播放请求，等就绪后自动开始 */
    private var pendingAutoStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.texture_view)
        codecInfoText = findViewById(R.id.text_info)
        loopSwitch = findViewById(R.id.switch_loop)
        textureView.surfaceTextureListener = this

        findViewById<Button>(R.id.btn_pick).setOnClickListener {
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
            }
            @Suppress("DEPRECATION")
            startActivityForResult(pick, REQUEST_PICK_VIDEO)
        }
        findViewById<Button>(R.id.btn_sample).setOnClickListener {
            // 内置示例（res/raw/sample_video.mp4），无需外部文件即可验证播放链路
            videoUri = Uri.parse("android.resource://$packageName/${R.raw.sample_video}")
            startPlayback()
        }
        findViewById<Button>(R.id.btn_play).setOnClickListener { startPlayback() }
        findViewById<Button>(R.id.btn_stop).setOnClickListener { stopPlayback() }
        loopSwitch.setOnCheckedChangeListener { _, checked ->
            decodeThread?.looping = checked
        }

        // 启动时在 logcat 里输出全部解码器清单，便于确认本机的硬解组件
        Log.i(TAG, CodecSelector.dumpAllDecoders())

        handleViewIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(intent: Intent?) {
        // 命令行/外部调用入口，两种方式（App 为 singleTask，播放中可热切换视频）：
        //   adb shell am start -n com.example.simpleplayer/.MainActivity \
        //       --es videoPath /sdcard/Download/test.mp4
        //   adb shell am start -n com.example.simpleplayer/.MainActivity \
        //       -a android.intent.action.VIEW -d "file:///sdcard/Download/test.mp4"
        val extraPath = intent?.getStringExtra(EXTRA_VIDEO_PATH)
        if (!extraPath.isNullOrEmpty()) {
            playUri(pathToUri(extraPath))
            return
        }
        // 显式组件启动时 action 可能为空，只要带 data 就播
        val data = intent?.data
        if (data != null) {
            playUri(if (data.scheme == null) Uri.fromFile(File(data.toString())) else data)
        }
    }

    /** 兼容裸路径、file://、content:// 三种写法 */
    private fun pathToUri(path: String): Uri = when {
        path.startsWith("content://") || path.startsWith("file://") -> Uri.parse(path)
        else -> Uri.fromFile(File(path))
    }

    private fun playUri(uri: Uri) {
        videoUri = uri
        toast("播放：$uri")
        startPlayback()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_VIDEO && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                // 持久化读权限，重启 App 后仍能直接播放上次选的文件
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                }
                videoUri = uri
                startPlayback()
            }
        }
    }

    private fun startPlayback() {
        val uri = videoUri
        if (uri == null) {
            toast("请先选择一个视频文件")
            return
        }
        if (!textureView.isAvailable) {
            pendingAutoStart = true
            return
        }
        stopPlayback()

        val surface = Surface(textureView.surfaceTexture)
        decodeThread = VideoDecodeThread(applicationContext, uri, surface).apply {
            looping = loopSwitch.isChecked
            callbacks = this@MainActivity
            start()
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun stopPlayback() {
        decodeThread?.let {
            it.callbacks = null // 退出后不再回调 UI
            it.requestStop()
        }
        decodeThread = null
        pendingAutoStart = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---------- VideoDecodeThread.Callbacks（均在主线程回调） ----------

    override fun onDecoderSelected(codecName: String) {
        val desc = CodecSelector.describeCodec(codecName)
        codecInfoText.text = "解码器：$codecName（$desc）"
    }

    override fun onVideoSizeChanged(width: Int, height: Int) {
        adjustAspectRatio(width, height)
        // 让 SurfaceTexture 缓冲按视频原始分辨率分配，避免缩放模糊
        textureView.surfaceTexture?.setDefaultBufferSize(width, height)
    }

    override fun onPlaybackEnded() {
        toast("播放结束")
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPlaybackStopped() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onError(message: String) {
        codecInfoText.text = message
        toast(message)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---------- TextureView 生命周期 ----------

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (pendingAutoStart && videoUri != null) {
            startPlayback()
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        stopPlayback()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    // ---------- 其他 ----------

    /** 按视频宽高比调整 TextureView 尺寸（居中、不裁切、不拉伸） */
    private fun adjustAspectRatio(videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0) return
        val container = textureView.parent as? View ?: return
        val cw = container.width
        val ch = container.height
        if (cw == 0 || ch == 0) {
            // 布局还没完成，等一帧再试
            textureView.post { adjustAspectRatio(videoWidth, videoHeight) }
            return
        }
        val scale = min(cw.toFloat() / videoWidth, ch.toFloat() / videoHeight)
        val w = (videoWidth * scale).toInt().coerceAtLeast(1)
        val h = (videoHeight * scale).toInt().coerceAtLeast(1)
        textureView.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
    }

    companion object {
        private const val TAG = "SimplePlayer"
        private const val REQUEST_PICK_VIDEO = 1001
        const val EXTRA_VIDEO_PATH = "videoPath" // am start --es videoPath /sdcard/xxx.mp4
    }
}
