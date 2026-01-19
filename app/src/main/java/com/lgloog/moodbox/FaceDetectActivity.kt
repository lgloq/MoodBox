package com.lgloog.moodbox

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class FaceDetectActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var tvStatus: TextView
    private lateinit var btnClose: ImageButton
    private lateinit var btnSwitch: ImageButton

    private lateinit var cameraExecutor: ExecutorService

    // 【核心修复 1】引入 Session ID (会话令牌)
    // 每次启动/切换相机，ID +1。只有持有最新 ID 的结果才会被处理。
    private var currentSessionId = 0L

    // 是否已经找到结果 (防止同一次会话中重复触发)
    private var isResultFound = false

    private var currentCameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
    private var finishRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_face_detect)

        viewFinder = findViewById(R.id.viewFinder)
        tvStatus = findViewById(R.id.tvStatus)
        btnClose = findViewById(R.id.btnClose)
        btnSwitch = findViewById(R.id.btnSwitch)

        btnClose.setOnClickListener { finish() }

        btnSwitch.setOnClickListener {
            switchCamera()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun switchCamera() {
        // 1. 暴力打断之前的倒计时
        finishRunnable?.let { viewFinder.removeCallbacks(it) }

        // 2. 【核心】更新 Session ID，旧的号码瞬间作废
        currentSessionId++

        // 3. 重置状态
        isResultFound = false
        tvStatus.text = "正在切换摄像头..."

        // 4. 切换镜头方向
        currentCameraSelector = if (currentCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }

        // 5. 重启相机
        startCamera()
    }

    private fun startCamera() {
        // 【核心】捕获当前启动时的 Session ID
        // 这个 captureSessionId 会被“闭包”进下面的 Analyzer 里
        // 也就是说，这个相机实例产生的所有结果，都会永远带着这个 ID
        val captureSessionId = currentSessionId

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, FaceAnalyzer { moodType ->
                        // 回调时，把它的“出身证明” (ID) 也带回来
                        handleMoodResult(moodType, captureSessionId)
                    })
                }

            try {
                cameraProvider.unbindAll()

                if (!isDestroyed && !isFinishing) {
                    cameraProvider.bindToLifecycle(
                        this, currentCameraSelector, preview, imageAnalyzer
                    )

                    val lensName = if (currentCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) "前置" else "后置"
                    tvStatus.text = "AI 视觉($lensName)已激活..."
                }

            } catch (exc: Exception) {
                Log.e(TAG, "相机启动失败", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // 处理结果 (注意这里加了第二个参数：resultSessionId)
    private fun handleMoodResult(moodType: String, resultSessionId: Long) {
        // 必须在主线程进行 ID 校验，保证线程安全
        runOnUiThread {
            // ================== 【终极防线】 ==================

            // 1. 验票：如果这个结果的 ID 不等于当前的 ID (说明是前朝遗老)
            // 直接扔掉，不做任何处理！
            if (resultSessionId != currentSessionId) {
                Log.d(TAG, "拦截到一个过期结果: $resultSessionId (当前: $currentSessionId)")
                return@runOnUiThread
            }

            // 2. 防重复：如果当前会话已经出过结果了，也忽略
            if (isResultFound) {
                return@runOnUiThread
            }
            // ================================================

            // 校验通过，锁定状态
            isResultFound = true

            tvStatus.text = "检测完成！心情判定: $moodType"

            finishRunnable = Runnable {
                // 再次验票 (防止在倒计时期间切换了)
                if (resultSessionId != currentSessionId) return@Runnable

                val resultIntent = Intent()
                resultIntent.putExtra("mood_result", moodType)
                setResult(RESULT_OK, resultIntent)
                finish()
            }
            viewFinder.postDelayed(finishRunnable!!, 2000)
        }
    }

    // FaceAnalyzer 保持不变，它只负责无脑产出结果
    private class FaceAnalyzer(private val onMoodDetected: (String) -> Unit) : ImageAnalysis.Analyzer {
        private val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()
        private val detector = FaceDetection.getClient(options)

        @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                detector.process(image)
                    .addOnSuccessListener { faces ->
                        if (faces.isNotEmpty()) {
                            val face = faces[0]
                            val smileProb = face.smilingProbability ?: 0f
                            val moodType = when {
                                smileProb > 0.6 -> "poetry"
                                smileProb < 0.2 -> "joke"
                                else -> "soup"
                            }
                            onMoodDetected(moodType)
                        }
                    }
                    .addOnCompleteListener { imageProxy.close() }
            } else {
                imageProxy.close()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        finishRunnable?.let { viewFinder.removeCallbacks(it) }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "需要权限", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    companion object {
        private const val TAG = "FaceDetect"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}