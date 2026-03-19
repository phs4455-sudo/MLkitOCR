package com.hd.hdmobilepos.activity.scan

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.hd.hdmobilepos.R
import com.hd.hdmobilepos.model.MrzOverlayView
import com.hd.hdmobilepos.model.PassportMRZ
import com.hd.hdmobilepos.util.MRZUtils
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class HDPassportScanActivity : AppCompatActivity() {

    private enum class MrzOutcome {
        /** 완전 유효 MRZ(체크디지트 포함) */
        SUCCESS,
        /** 핵심 필드만 유효(개인번호/최종CD 불확실). 연속 프레임 안정화 후 SUCCESS로 승격 */
        PROGRESS,
        /** MRZ 후보 없음 */
        NONE
    }

    private enum class DemoHintType { MRZ, HPOINT }

    companion object {
        private val COLOR_SCANNING = Color.WHITE
        private val COLOR_SUCCESS = Color.parseColor("#2ECC71")
        private val COLOR_ERROR = Color.parseColor("#FF3B30")

        /** UI 안내 문구 업데이트 간격(너무 자주 바뀌면 정신없음) */
        private const val RETRY_INTERVAL_MS = 700L

        /** 프레임 분석(MLKit) 최소 간격 - PDA에서 체감 속도/발열 균형용 */
        // 기존 소스 대비 체감이 느려졌다는 피드백이 있어, 기본 간격을 조금 더 공격적으로 잡습니다.
        // (단, 기기 발열/프레임 드랍이 심하면 160~200ms로 되돌려도 됩니다.)
        private const val MIN_PROCESS_INTERVAL_MS = 140L

        /** 바코드(MLKit) 분석 최소 간격 - MRZ 인식 속도를 위해 과도한 바코드 스캔을 줄입니다 */
        // MRZ가 화면에 크게 들어왔을 때는 바코드 스캔이 MRZ 인식을 "막"지 않도록 더 뜸하게 수행합니다.
        private const val BARCODE_PROCESS_INTERVAL_MS = 1600L

        /** MRZ가 "화면에 있다"고 판단되면 일정 시간 바코드 스캔을 멈추고 MRZ 우선 처리 */
        private const val MRZ_PRIORITY_DURATION_MS = 2800L

        /** 스캔 화면 자동 종료 시간(리소스 보호) */
        private const val SCAN_SCREEN_TIMEOUT_MS = 30_000L

        /** 탭/자동 포커스 재시도 최소 간격 */
        private const val AUTOFOCUS_INTERVAL_MS = 2200L

        /** 선명도(blur) 체크 간격 */
        private const val SHARPNESS_CHECK_INTERVAL_MS = 900L

        /** 선명도 임계값(경험적) - 값이 낮으면 흐림으로 간주 */
        // 기기별 편차가 커서 '고정 임계값'은 보조로만 사용합니다(동적 임계값 + 연속 프레임 조건 사용)
        private const val SHARPNESS_BLUR_THRESHOLD = 2.2

        // --- SP60 전용(경험치) ---
        // SP60에서 "MRZ가 박스에 크게 들어왔는데도" 근접 인식이 잘 안 되는 경우가 많아,
        // - Continuous AF + 박스 하단(텍스트) 기준 포커스
        // - 기본 디지털 줌을 적용해(필요 시 핀치로 조절) 인식 성공률을 끌어올립니다.
        private const val SP60_INITIAL_ZOOM_RATIO = 1.4f
        private const val SP60_MAX_ZOOM_RATIO = 2.4f
        private const val SP60_AUTO_ZOOM_STEP = 0.10f

        // SP60에서 AF를 더 자주 "킥"해주면 근접에서 초점이 더 빨리/안정적으로 잡히는 경향이 있습니다.
        private const val SP60_AF_KICK_INTERVAL_MS = 2500L

        private const val TAG = "HDPassportScan"
    }

    private lateinit var previewView: PreviewView
    private lateinit var mrzOverlay: MrzOverlayView
    private lateinit var tvHint: TextView
    private lateinit var tvSubHint: TextView
    private lateinit var btnClose: View
    private lateinit var btnFlash: ImageButton

    // --- 상단 데모 힌트(이미지 기반) ---
    private lateinit var demoHintContainer: View
    private lateinit var demoHintCard: View
    private lateinit var demoHintImage: ImageView
    private lateinit var demoHintTitle: TextView
    private lateinit var demoHintSubtitle: TextView

    private var demoHintType: DemoHintType = DemoHintType.MRZ
    private var demoHintCarouselRunning: Boolean = false
    private val demoHintCarouselIntervalMs: Long = 2600L
    private val demoHintCarouselRunnable = object : Runnable {
        override fun run() {
            if (!demoHintCarouselRunning) return
            if (!this@HDPassportScanActivity::demoHintContainer.isInitialized) return

            val now = System.currentTimeMillis()

            // MRZ 우선 모드에서는 MRZ 안내를 고정(바코드 안내로 바뀌지 않게)
            val lockMrz = now < mrzPriorityUntilMs
            val next = if (lockMrz) {
                DemoHintType.MRZ
            } else {
                if (demoHintType == DemoHintType.MRZ) DemoHintType.HPOINT else DemoHintType.MRZ
            }

            updateDemoHint(next, animate = true)
            positionDemoHintAboveGuide()

            demoHintContainer.postDelayed(this, demoHintCarouselIntervalMs)
        }
    }


    // --- 스캔 화면 자동 종료(30초) ---
    private val scanTimeoutRunnable = Runnable {
        onScanTimeout()
    }

    private lateinit var topBar: View
    private lateinit var bottomPanel: View

    private var imageAnalysis: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null

    private lateinit var textRecognizer: TextRecognizer
    private lateinit var barcodeScanner: BarcodeScanner
    private lateinit var cameraExecutor: ExecutorService

    private val isFinished = AtomicBoolean(false)
    private val isAnalyzing = AtomicBoolean(false)
    @Volatile
    private var isUiAlive: Boolean = false

    private var lastUiFeedbackTimeMs: Long = 0L
    private var lastProgressUiMs: Long = 0L
    private var lastProcessStartMs: Long = 0L
    private var lastAutoFocusMs: Long = 0L

    private var lastSharpnessCheckMs: Long = 0L
    private var lastSharpnessScore: Double = 0.0

    // 선명도는 기기/해상도에 따라 분포가 크게 달라서 EMA(지수이동평균)로 기준선을 잡습니다.
    private var sharpnessEma: Double = 0.0
    private var sharpnessSamples: Int = 0
    private var blurConsecutiveCount: Int = 0

    // 바코드 스캔은 MRZ보다 가벼운 편이지만, 매 프레임 돌리면 MRZ 인식이 체감상 느려집니다.
    private var lastBarcodeScanMs: Long = 0L

    // --- MRZ 우선 모드 ---
    // MRZ가 "화면에 크게 들어온" 상황에서는 바코드 스캔이 MRZ 인식 시도를 가로막지 않도록,
    // 일정 시간 동안 바코드 스캔을 멈추고(MRZ 우선) MRZ 인식에 집중합니다.
    private var mrzPriorityUntilMs: Long = 0L

    // --- Relaxed MRZ(핵심 필드만 검증) 안정화 ---
    // 개인번호/최종 체크디지트는 OCR에서 흔히 깨지므로(특히 가까이 촬영/하단 노이즈),
    // "핵심 필드"만 통과한 후보는 2프레임 연속 동일할 때 성공 처리(정확도+속도 균형).
    private var weakMrzStableCount: Int = 0
    private var lastWeakMrzKey: String? = null

    private var isTorchOn: Boolean = false

    // --- MRZ upside-down(180도) 지원 ---
    private var consecutiveMrzFails: Int = 0
    private var flip180Enabled: Boolean = false
    private var flip180Toggle: Boolean = false

    // --- Resolution / aspect tuning ---
    private var analysisSize: Size = Size(1280, 720)
    private var targetAspectRatio: Int = AspectRatio.RATIO_4_3

    // --- Zoom (pinch) ---
    private lateinit var scaleDetector: ScaleGestureDetector
    private var currentZoomRatio: Float = 1f

    // SP60: 초기 줌을 낮추면(프레이밍 개선) 간혹 MRZ가 너무 작아질 수 있어,
    // MRZ가 화면에 있다고 판단되는 동안에는(우선 모드) 필요 시 2.0x까지 천천히 올립니다.
    private var lastSp60AutoZoomMs: Long = 0L

    // --- Device detect ---
    private val isSp60: Boolean by lazy {
        val m = (Build.MODEL ?: "").uppercase(Locale.ROOT)
        val d = (Build.DEVICE ?: "").uppercase(Locale.ROOT)
        val p = (Build.PRODUCT ?: "").uppercase(Locale.ROOT)
        m.contains("SP60") || d.contains("SP60") || p.contains("SP60")
    }


    private val permissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                bindCameraUseCases()
            } else {
                showToast("카메라 권한이 필요합니다")
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isUiAlive = true
        setContentView(R.layout.activity_passport_scanner)

        initViews()
        initMlKit()
        cameraExecutor = Executors.newSingleThreadExecutor()

        setScanningUi()
        checkCameraPermissionAndStart()
    }

    override fun onResume() {
        super.onResume()
        isUiAlive = true
        mrzOverlay.post {
            mrzOverlay.startScanAnimation()
            positionDemoHintAboveGuide()
        }
        startDemoHintCarousel()
        scheduleScanTimeout()

        if (!isFinished.get()) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            if (granted && cameraProvider == null) {
                bindCameraUseCases()
            }
        }
    }

    override fun onPause() {
        isUiAlive = false
        super.onPause()
        cancelScanTimeout()
        stopDemoHintCarousel()
        mrzOverlay.stopScanAnimation()
        stopCamera()
    }

    override fun onDestroy() {
        isUiAlive = false
        super.onDestroy()
        cancelScanTimeout()
        stopDemoHintCarousel()
        stopCamera()

        try {
            textRecognizer.close()
        } catch (_: Exception) {
        }
        try {
            barcodeScanner.close()
        } catch (_: Exception) {
        }

        if (!cameraExecutor.isShutdown) {
            cameraExecutor.shutdownNow()
        }
    }

    private fun initViews() {
        previewView = findViewById(R.id.previewView)
        mrzOverlay = findViewById(R.id.mrzOverlay)
        tvHint = findViewById(R.id.tvHint)
        tvSubHint = findViewById(R.id.tvSubHint)
        btnClose = findViewById(R.id.btnClose)
        btnFlash = findViewById(R.id.btnFlash)

        topBar = findViewById(R.id.topBar)
        bottomPanel = findViewById(R.id.bottomPanel)

        // 상단 가이드: 기존 Overlay 데모 애니메이션은 끄고(중복 방지),
        // 이미지 기반 데모 힌트를 사용합니다.
        mrzOverlay.setShowDemoAnimation(false)

        demoHintContainer = findViewById(R.id.demoHintContainer)
        demoHintCard = findViewById(R.id.demoHintCard)
        demoHintImage = findViewById(R.id.demoHintImage)
        demoHintTitle = findViewById(R.id.demoHintTitle)
        demoHintSubtitle = findViewById(R.id.demoHintSubtitle)

        updateDemoHint(DemoHintType.MRZ, animate = false)
        mrzOverlay.post { positionDemoHintAboveGuide() }

        btnClose.setOnClickListener {
            cancelScanTimeout()
            setResult(RESULT_CANCELED)
            finish()
        }

        btnFlash.setOnClickListener { toggleTorch() }

        // Preview 품질(텍스처 기반) 우선
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        // SP60(세로 긴 화면 + 4:3 프리뷰)에서 FILL_CENTER는 좌/우가 잘려 보일 수 있어요.
        // FIT_CENTER로 전체 프리뷰를 보여주면, 사용자가 MRZ 양끝을 박스 안에 정확히 맞출 수 있습니다.
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        previewView.setBackgroundColor(Color.BLACK)

        // Pinch zoom
        scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val cam = camera ?: return false
                    val zoomState = cam.cameraInfo.zoomState.value ?: return false
                    val minZoom = zoomState.minZoomRatio
                    val maxZoom = zoomState.maxZoomRatio

                    val next = (currentZoomRatio * detector.scaleFactor)
                        .coerceIn(minZoom, maxZoom)

                    try {
                        cam.cameraControl.setZoomRatio(next)
                        currentZoomRatio = next
                    } catch (_: Exception) {
                    }
                    return true
                }
            }
        )

        // Tap focus + pinch zoom 같이 처리
        previewView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)

            if (event.action == MotionEvent.ACTION_UP && !scaleDetector.isInProgress) {
                startFocusAt(event.x, event.y)
            }
            true
        }
    }

    private fun initMlKit() {
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        // H.Point는 포맷을 확정하지 못했으니 ALL_FORMATS 유지(패턴으로 필터)
        val barcodeOptions = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
        barcodeScanner = BarcodeScanning.getClient(barcodeOptions)
    }

    private fun checkCameraPermissionAndStart() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            bindCameraUseCases()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun bindCameraUseCases() {
        if (isFinished.get()) return

        if (previewView.width == 0 || previewView.height == 0) {
            previewView.post { bindCameraUseCases() }
            return
        }

        applyDeviceTuning()

        val cameraProviderFuture: ListenableFuture<ProcessCameraProvider> =
            ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(
            {
                try {
                    cameraProvider = cameraProviderFuture.get()

                    val previewBuilder = Preview.Builder()
                        .setTargetAspectRatio(targetAspectRatio)
                        .setTargetRotation(previewView.display.rotation)

                    // SP60: continuous AF를 강제(가능한 범위 내에서)
                    try {
                        val extender = Camera2Interop.Extender(previewBuilder)
                        extender.setCaptureRequestOption(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                        )
                        extender.setCaptureRequestOption(
                            CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_ON
                        )
                    } catch (_: Exception) {
                    }

                    val preview = previewBuilder.build()
                    preview.setSurfaceProvider(previewView.surfaceProvider)

                    val analysisBuilder = ImageAnalysis.Builder()
                        .setTargetResolution(analysisSize)
                        .setTargetRotation(previewView.display.rotation)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setImageQueueDepth(1)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)

                    try {
                        val extender = Camera2Interop.Extender(analysisBuilder)
                        extender.setCaptureRequestOption(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                        )
                        extender.setCaptureRequestOption(
                            CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_ON
                        )
                    } catch (_: Exception) {
                    }

                    imageAnalysis = analysisBuilder
                        .build()
                        .also {
                            it.setAnalyzer(cameraExecutor, MrzAnalyzer())
                        }

                    val selector = CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build()

                    cameraProvider?.unbindAll()
                    camera = cameraProvider?.bindToLifecycle(this, selector, preview, imageAnalysis)

                    updateTorchUi()

                    // SP60: MRZ를 너무 멀리 두어야만 인식되는 현상을 완화하기 위해,
                    // - Continuous AF + 박스 하단(텍스트) 기준 포커스
                    // - 기본 디지털 줌을 적용(필요 시 핀치로 조절 가능)
                    previewView.post {
                        autoFocusOnGuide(force = true)
                        setInitialZoomIfPossible(if (isSp60) SP60_INITIAL_ZOOM_RATIO else 1.0f)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    showToast("카메라 초기화 실패")
                    finish()
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun stopCamera() {
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null

        cameraProvider?.unbindAll()
        cameraProvider = null
        camera = null
    }


    private fun scheduleScanTimeout() {
        if (!this::previewView.isInitialized) return
        previewView.removeCallbacks(scanTimeoutRunnable)
        previewView.postDelayed(scanTimeoutRunnable, SCAN_SCREEN_TIMEOUT_MS)
    }

    private fun cancelScanTimeout() {
        if (!this::previewView.isInitialized) return
        previewView.removeCallbacks(scanTimeoutRunnable)
    }

    private fun onScanTimeout() {
        if (isFinished.get()) return
        if (!isFinished.compareAndSet(false, true)) return

        runWhenUiAlive {
            try {
                stopDemoHintCarousel()
            } catch (_: Exception) {
            }

            try {
                mrzOverlay.setGuideColor(COLOR_ERROR)
                mrzOverlay.stopScanAnimation()
            } catch (_: Exception) {
            }

            stopCamera()

            showToast("스캔 시간이 초과되어 종료합니다.")
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun startFocusAt(x: Float, y: Float, autoCancelSeconds: Long = 2L) {
        val cam = camera ?: return
        val meteringPoint = previewView.meteringPointFactory.createPoint(x, y)

        val action = FocusMeteringAction.Builder(
            meteringPoint,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        )
            .setAutoCancelDuration(autoCancelSeconds, TimeUnit.SECONDS)
            .build()

        // 일부 PDA에서는 이전 메터링이 남아 있으면 AF가 점점 불안정해질 수 있어,
        // 새 메터링을 시작하기 전에 한 번 cancel 해주는 편이 안정적입니다.
        try {
            cam.cameraControl.cancelFocusAndMetering()
        } catch (_: Exception) {
        }

        try {
            cam.cameraControl.startFocusAndMetering(action)
        } catch (_: Exception) {
        }
    }

    private fun autoFocusOnGuide(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val interval = if (isSp60) SP60_AF_KICK_INTERVAL_MS else AUTOFOCUS_INTERVAL_MS
        if (!force && now - lastAutoFocusMs < interval) return
        lastAutoFocusMs = now

        val rect = mrzOverlay.guideRect
        if (rect.width() <= 0f || rect.height() <= 0f) return

        // MRZ 텍스트는 박스 하단 쪽에 위치하는 경우가 많아서(특히 샘플 여권),
        // 중앙보다 약간 아래를 포커스 기준점으로 잡습니다.
        val focusX = rect.centerX()
        val focusY = rect.top + rect.height() * 0.72f
        startFocusAt(focusX, focusY, autoCancelSeconds = if (isSp60) 6L else 3L)
    }

    private fun setInitialZoomIfPossible(desiredZoomRatio: Float) {
        val cam = camera ?: return
        val zoomState = cam.cameraInfo.zoomState.value ?: return

        val z = desiredZoomRatio.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)

        // 불필요한 호출 방지
        if (abs(z - currentZoomRatio) < 0.01f) return

        try {
            cam.cameraControl.setZoomRatio(z)
            currentZoomRatio = z
        } catch (_: Exception) {
        }
    }

    /**
     * SP60에서 프레이밍(박스 안에 MRZ 전체가 들어오게)을 위해 초기 줌을 낮췄기 때문에,
     * MRZ가 화면에 "있는" 상태(mrzPriority)인데도 인식이 계속 실패하면
     * 2.0x까지 천천히(과하지 않게) 줌을 올려 인식률을 보완합니다.
     *
     * - blur(근접/초점 불안)일 때는 줌을 올려도 도움이 적고, 오히려 프레이밍만 어려워질 수 있어 스킵합니다.
     * - 상한은 2.0x로 제한(박스 프레이밍 문제 재발 방지)
     */
    private fun sp60MaybeStepZoomIn(isBlurry: Boolean) {
        if (!isSp60) return
        if (isBlurry) return

        val now = System.currentTimeMillis()
        if (now >= mrzPriorityUntilMs) return
        if (now - lastSp60AutoZoomMs < 900L) return
        lastSp60AutoZoomMs = now

        val cam = camera ?: return
        val zoomState = cam.cameraInfo.zoomState.value ?: return

        val targetMax = min(zoomState.maxZoomRatio, 2.0f)
        if (currentZoomRatio >= targetMax - 0.01f) return

        val next = (currentZoomRatio + 0.10f).coerceIn(zoomState.minZoomRatio, targetMax)

        try {
            cam.cameraControl.setZoomRatio(next)
            currentZoomRatio = next
        } catch (_: Exception) {
        }
    }


    private fun maybeMaintainFocus(isBlurry: Boolean) {
        val now = System.currentTimeMillis()

        // blur일 때만 AF를 '킥'합니다.
        // (실패할 때마다 AF를 계속 걸면 오히려 AF 헌팅으로 선명도가 떨어질 수 있어요)
        if (isBlurry) {
            autoFocusOnGuide(force = false)
            return
        }

        // MRZ 우선 모드에서는(특히 SP60) 시간이 지나면 포커스가 떠버리는 느낌이 생길 수 있어,
        // 우선 모드가 유지되는 동안은 일정 간격으로 가이드 하단(텍스트) 기준 메터링을 갱신합니다.
        val refreshInterval = if (isSp60) 5000L else 3500L
        if (now < mrzPriorityUntilMs && now - lastAutoFocusMs > refreshInterval) {
            autoFocusOnGuide(force = true)
        }
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) {
            showToast("플래시를 지원하지 않는 기기입니다")
            return
        }

        isTorchOn = !isTorchOn
        cam.cameraControl.enableTorch(isTorchOn)
        updateTorchUi()
    }

    private fun updateTorchUi() {
        val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
        btnFlash.visibility = if (hasFlash) View.VISIBLE else View.INVISIBLE
        btnFlash.isEnabled = hasFlash
        btnFlash.setImageResource(
            if (isTorchOn) R.drawable.ic_flash_on_24 else R.drawable.ic_flash_off_24
        )
    }

    private inner class MrzAnalyzer : ImageAnalysis.Analyzer {
        @OptIn(ExperimentalGetImage::class)
        override fun analyze(imageProxy: ImageProxy) {
            if (isFinished.get()) {
                imageProxy.close()
                return
            }

            val now = System.currentTimeMillis()

            // 너무 자주/동시에 처리하지 않기(속도/발열/끊김 개선)
            if (!isAnalyzing.compareAndSet(false, true)) {
                imageProxy.close()
                return
            }
            if (now - lastProcessStartMs < MIN_PROCESS_INTERVAL_MS) {
                isAnalyzing.set(false)
                imageProxy.close()
                return
            }
            lastProcessStartMs = now

            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                finishFrame(imageProxy)
                return
            }

            // 선명도(blur) 체크는 가끔만 (EMA 기반 + 연속 프레임 조건)
            if (now - lastSharpnessCheckMs >= SHARPNESS_CHECK_INTERVAL_MS) {
                val score = estimateSharpnessFromYPlane(imageProxy)
                lastSharpnessCheckMs = now

                if (score > 0.1) {
                    lastSharpnessScore = score

                    sharpnessSamples++
                    sharpnessEma = if (sharpnessSamples == 1) {
                        score
                    } else {
                        sharpnessEma * 0.85 + score * 0.15
                    }

                    // 동적 임계값: 현재 평균 대비 너무 낮으면 blur로 판정
                    val dynThreshold = max(SHARPNESS_BLUR_THRESHOLD, sharpnessEma * 0.55)
                    val blurryNow = score < dynThreshold
                    blurConsecutiveCount = if (blurryNow) blurConsecutiveCount + 1 else 0
                } else {
                    // 측정 실패(0에 가까움)면 blur 메시지를 띄우지 않도록 리셋
                    blurConsecutiveCount = 0
                }
            }

            val baseRotation = imageProxy.imageInfo.rotationDegrees

            // MRZ가 "화면에 있다"고 판단되면 일정 시간 바코드 스캔을 멈추고 MRZ를 우선 처리합니다.
            val mrzPriority = now < mrzPriorityUntilMs

            // H.Point 바코드는 일정 간격으로만 스캔(매 프레임 돌리면 MRZ 인식이 느려짐)
            val shouldScanBarcode = !mrzPriority && (now - lastBarcodeScanMs >= BARCODE_PROCESS_INTERVAL_MS)

            // NOTE: 바코드 → MRZ를 "순차"로 돌리면(바코드가 없는 대부분의 프레임에서)
            //       MRZ 시작 자체가 늦어져 체감이 느려집니다.
            //       그래서 바코드 스캔이 필요한 프레임에서도 MRZ를 "동시에" 시작하고,
            //       둘 중 어떤 것이든 먼저 성공하면 종료되도록 합니다.

            val pending = java.util.concurrent.atomic.AtomicInteger(if (shouldScanBarcode) 2 else 1)
            val doneOnce = java.util.concurrent.atomic.AtomicBoolean(false)
            fun done() {
                if (pending.decrementAndGet() == 0 && doneOnce.compareAndSet(false, true)) {
                    finishFrame(imageProxy)
                }
            }

            if (shouldScanBarcode) {
                lastBarcodeScanMs = now
                val barcodeInput = InputImage.fromMediaImage(mediaImage, baseRotation)
                barcodeScanner.process(barcodeInput)
                    .addOnSuccessListener { barcodes ->
                        if (!shouldHandleAsyncCallback()) return@addOnSuccessListener
                        val hpoint = barcodes
                            .asSequence()
                            .mapNotNull { normalizeHpointBarcode(it.rawValue) }
                            .firstOrNull()
                        if (hpoint != null) {
                            onBarcodeRecognized(hpoint)
                        }
                    }
                    .addOnFailureListener {
                        // ignore
                    }
                    .addOnCompleteListener {
                        done()
                    }
            }

            // MRZ는 항상 시도(바코드가 없으면 대부분 MRZ가 목적)
            // 단, SP60에서 blur(초점이 떠있는 상태)일 때는 OCR을 계속 돌려도 대부분 실패 + 발열만 올라가서
            // 선명도 회복(AF)부터 우선시키는 편이 '체감 속도'와 인식률이 좋아집니다.
            val isBlurryNow = blurConsecutiveCount >= 2
            if (isSp60 && isBlurryNow) {
                maybeMaintainFocus(isBlurry = true)
                handleRetry(isBlurry = true)
                done() // MRZ OCR 스킵
            } else {
                runMrzTextRecognition(mediaImage, imageProxy, baseRotation, ::done)
            }
        }

        private fun runMrzTextRecognition(
            mediaImage: android.media.Image,
            imageProxy: ImageProxy,
            baseRotation: Int,
            onDone: () -> Unit
        ) {
            val rotationForMrz = chooseMrzRotation(baseRotation)
            val inputImage = InputImage.fromMediaImage(mediaImage, rotationForMrz)

            textRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    if (!shouldHandleAsyncCallback()) return@addOnSuccessListener
                    val outcome = onTextRecognized(visionText, imageProxy, rotationForMrz)

                    when (outcome) {
                        MrzOutcome.SUCCESS -> {
                            // 성공 시에는 onMrzRecognized()에서 finish 처리
                            consecutiveMrzFails = 0
                            flip180Enabled = false
                            return@addOnSuccessListener
                        }
                        MrzOutcome.PROGRESS -> {
                            // MRZ가 "화면에 있음"은 확실한데(핵심 필드 유효),
                            // 개인번호/최종 체크디지트가 흔들리는 상황.
                            // - 실패 카운트/에러 UI를 올리지 않고
                            // - MRZ 우선 모드로 잠깐 고정해 다음 프레임에서 안정화되도록 합니다.
                            consecutiveMrzFails = 0
                            flip180Enabled = false
                            bumpMrzPriority(System.currentTimeMillis())
                            return@addOnSuccessListener
                        }
                        MrzOutcome.NONE -> {
                            // 실패 처리
                            consecutiveMrzFails++
                        }
                    }

                    // 선명한데 계속 실패하면(특히 뒤집힘) 180도 시도 모드 활성화
                    val isBlurry = blurConsecutiveCount >= 2
                    if (!isBlurry && consecutiveMrzFails >= 4) {
                        flip180Enabled = true
                    }

                    if (flip180Enabled) {
                        // 다음 시도는 180도 회전도 번갈아가며 시도
                        flip180Toggle = !flip180Toggle
                    }

                    sp60MaybeStepZoomIn(isBlurry)
                    maybeMaintainFocus(isBlurry)
                    handleRetry(isBlurry = isBlurry)
                }
                .addOnFailureListener {
                    if (!shouldHandleAsyncCallback()) return@addOnFailureListener
                    maybeMaintainFocus(isBlurry = blurConsecutiveCount >= 2)
                    handleRetry(isBlurry = blurConsecutiveCount >= 2)
                }
                .addOnCompleteListener {
                    onDone()
                }
        }

        /**
         * @return
         * - SUCCESS: 체크디지트 포함 "완전" 유효 MRZ
         * - PROGRESS: 핵심 필드만 유효(2프레임 안정화 후 성공 처리)
         * - NONE: 후보 없음
         */
        private fun onTextRecognized(
            visionText: Text,
            imageProxy: ImageProxy,
            rotationUsed: Int
        ): MrzOutcome {
            // NOTE:
            // - 가까이 촬영되면 MRZ 1줄이 여러 조각으로 분리되어 인식되는 경우가 많습니다.
            // - 기존처럼 "top만 정렬"해서 합치면 (1행 조각/2행 조각이 섞이며) 후보 생성이 실패할 수 있습니다.
            // - 그래서 boundingBox 기반으로 "행(row) 클러스터링" 후, 같은 행은 left 순으로 이어붙여
            //   실제 읽는 순서(top→bottom / left→right)로 정렬된 라인 목록을 만든 뒤 MRZ 후보를 구성합니다.
            // - 마지막에는 체크디지트(유효성)로 필터링합니다.

            data class Seg(val cy: Int, val left: Int, val h: Int, val text: String)

            // ROI(가이드 박스) 주변만 우선 사용해서 노이즈 줄이기(속도/정확도 개선)
            val imgW: Float
            val imgH: Float
            if (rotationUsed == 90 || rotationUsed == 270) {
                imgW = imageProxy.height.toFloat()
                imgH = imageProxy.width.toFloat()
            } else {
                imgW = imageProxy.width.toFloat()
                imgH = imageProxy.height.toFloat()
            }

            val roiExpanded: RectF? = try {
                val roi = mapPreviewRectToImageRect(mrzOverlay.guideRect, previewView, imageProxy, rotationUsed)
                val r = RectF(roi)

                // 매핑 오차/기울기 대비: 위아래 여유를 넉넉히
                val padX = r.width() * 0.12f
                val padY = r.height() * 0.35f
                r.inset(-padX, -padY)

                // clamp
                r.left = r.left.coerceIn(0f, imgW)
                r.right = r.right.coerceIn(0f, imgW)
                r.top = r.top.coerceIn(0f, imgH)
                r.bottom = r.bottom.coerceIn(0f, imgH)

                if (r.width() < 10f || r.height() < 10f) null else r
            } catch (_: Exception) {
                null
            }


            val allSegs = ArrayList<Seg>(48)
            val roiSegs = ArrayList<Seg>(32)

            for (block in visionText.textBlocks) {
                for (line in block.lines) {
                    val bbox = line.boundingBox ?: continue
                    val t = line.text
                    if (t.isBlank()) continue

                    val cy = (bbox.top + bbox.bottom) / 2
                    val h = (bbox.bottom - bbox.top).coerceAtLeast(1)
                    val seg = Seg(cy = cy, left = bbox.left, h = h, text = t)
                    allSegs.add(seg)

                    if (roiExpanded != null) {
                        val rf = RectF(bbox)
                        if (RectF.intersects(roiExpanded, rf)) {
                            roiSegs.add(seg)
                        }
                    }
                }
            }

            val segs = if (roiExpanded != null) roiSegs else allSegs
            if (roiExpanded != null && roiSegs.isEmpty()) return MrzOutcome.NONE
            if (segs.isEmpty()) return MrzOutcome.NONE

            // 대표 높이(중앙값 기반)로 같은 "행" 판단 임계값 계산
            val heights = segs.map { it.h }.sorted()
            val medianH = heights[heights.size / 2].coerceAtLeast(10)
            val rowThreshold = (medianH * 0.55f).toInt().coerceAtLeast(12)

            // 1) top(=centerY) → left 순으로 정렬
            val sorted = segs.sortedWith(compareBy<Seg> { it.cy }.thenBy { it.left })

            // 2) centerY가 가까운 것끼리 row로 묶고, row 안에서는 left 순으로 이어붙임
            val rows = ArrayList<String>(24)
            var currentRow = ArrayList<Seg>(8)
            var currentCy = sorted.first().cy

            fun flushRow() {
                if (currentRow.isEmpty()) return
                val rowText = currentRow
                    .sortedBy { it.left }
                    .joinToString(separator = "") { it.text }
                if (rowText.isNotBlank()) rows.add(rowText)
                currentRow = ArrayList(8)
            }

            for (s in sorted) {
                if (abs(s.cy - currentCy) <= rowThreshold) {
                    currentRow.add(s)
                    // running average로 currentCy 업데이트(행이 살짝 기울어져도 안정)
                    currentCy = ((currentCy * (currentRow.size - 1)) + s.cy) / currentRow.size
                } else {
                    flushRow()
                    currentRow.add(s)
                    currentCy = s.cy
                }
            }
            flushRow()

            if (rows.isEmpty()) return MrzOutcome.NONE

            val nowMs = System.currentTimeMillis()

            // MRZ가 화면에 크게 들어왔는데도 인식이 안 되는 케이스는,
            // 1) 체크디지트가 흔들리거나(특히 personal/final)
            // 2) 바코드 스캔이 MRZ 시도를 가로막는 경우가 많습니다.
            // -> rows에서 MRZ 특성이 보이면(휴리스틱) 잠깐 MRZ 우선 모드로 전환합니다.
            val mrzLikely = run {
                fun score(s: String): Int {
                    val t = s.replace(" ", "").uppercase(Locale.ROOT)
                    val angles = t.count { it == '<' }
                    var sc = 0
                    if (t.contains("P<")) sc += 6
                    sc += min(12, angles)
                    if (t.length >= 26) sc += 2
                    return sc
                }
                val strong = rows.count { score(it) >= 10 }
                strong >= 2
            }
            if (mrzLikely) bumpMrzPriority(nowMs)

            // row 순서 기반으로 MRZ 후보 생성(정상/역순 두 번 시도)
            val candidate =
                tryBuildMrzCandidate(rows) ?: tryBuildMrzCandidate(rows.asReversed())

            if (candidate != null) {
                bumpMrzPriority(nowMs)

                val mrzText = candidate.line1 + "\n" + candidate.line2

                // core/strict 모두 업무에 필요한 필드(여권번호/생년월일/만료일)는 충분히 신뢰할 수 있으므로
                // 즉시 성공 처리합니다. (SP60 근접 촬영에서 personal/final 영역 흔들림으로 인한 지연 방지)
                weakMrzStableCount = 0
                lastWeakMrzKey = null
                onMrzRecognized(PassportMRZ(mrzText))
                return MrzOutcome.SUCCESS
            }

            return MrzOutcome.NONE
        }

        private fun tryBuildMrzCandidate(lines: List<String>): MRZUtils.MrzCandidate? {
            // rows(라인) 기반으로 후보를 만들고,
            // 1) strict(개인번호/최종CD 포함) 먼저
            // 2) strict가 안 되면 core(여권번호/생년월일/만료일)만 통과한 후보를 반환
            //    → Activity에서 2프레임 안정화 후 성공 처리
            return MRZUtils.findBestTd3PassportMrzCandidateFromRows(lines)
        }
    }

    private fun finishFrame(imageProxy: ImageProxy) {
        try {
            imageProxy.close()
        } catch (_: Exception) {
        }
        isAnalyzing.set(false)
    }

    /**
     * MRZ upside-down(180도) 지원을 위해, 일정 실패 이후에는 0/180을 번갈아 시도합니다.
     * - 정상 촬영: baseRotation
     * - 뒤집은 촬영: (baseRotation + 180) % 360
     */
    private fun chooseMrzRotation(baseRotation: Int): Int {
        return if (flip180Enabled && flip180Toggle) {
            (baseRotation + 180) % 360
        } else {
            baseRotation
        }
    }

    private fun bumpMrzPriority(nowMs: Long) {
        mrzPriorityUntilMs = max(mrzPriorityUntilMs, nowMs + MRZ_PRIORITY_DURATION_MS)

        // MRZ가 화면에 들어온 상태로 판단되면, 상단 데모 힌트도 MRZ로 고정합니다.
        updateDemoHint(DemoHintType.MRZ, animate = true)
        positionDemoHintAboveGuide()
    }

    /**
     * H.Point 바코드 규칙:
     * - 총 16자리 숫자
     * - 앞 4자리 "7500"으로 시작
     *
     * rawValue가 공백/하이픈을 포함하는 경우가 있어서 정규화 후 판정합니다.
     */
    private fun normalizeHpointBarcode(rawValue: String?): String? {
        if (rawValue.isNullOrBlank()) return null

        var s = rawValue.trim().replace(Regex("\\s+"), "")
        s = s.replace("-", "")

        if (s.length != 16) return null
        if (!s.startsWith("7500")) return null
        if (!s.all { it.isDigit() }) return null

        return s
    }

    private fun onBarcodeRecognized(value: String) {
        if (!isFinished.compareAndSet(false, true)) return

        runWhenUiAlive {
            mrzOverlay.setGuideColor(COLOR_SUCCESS)
            mrzOverlay.stopScanAnimation()
            vibrateSuccess()

            cancelScanTimeout()
            stopCamera()

            setResult(RESULT_OK, PassportScanContract.createBarcodeResultIntent(value))
            finish()
        }
    }

    private fun onMrzRecognized(passport: PassportMRZ) {
        if (!isFinished.compareAndSet(false, true)) return

        runWhenUiAlive {
            mrzOverlay.setGuideColor(COLOR_SUCCESS)
            mrzOverlay.stopScanAnimation()
            vibrateSuccess()

            cancelScanTimeout()
            stopCamera()

            setResult(RESULT_OK, PassportScanContract.createPassportResultIntent(passport))
            finish()
        }
    }

    /**
     * PreviewView의 가이드 사각형(뷰 좌표)을 MLKit boundingBox와 동일한 이미지 좌표(회전 보정된 upright 기준)로 매핑합니다.
     */
    private fun mapPreviewRectToImageRect(
        roiView: RectF,
        previewView: PreviewView,
        imageProxy: ImageProxy,
        rotationDegrees: Int
    ): RectF {
        val viewW = previewView.width.toFloat()
        val viewH = previewView.height.toFloat()

        if (viewW <= 0f || viewH <= 0f) {
            return RectF(0f, 0f, imageProxy.width.toFloat(), imageProxy.height.toFloat())
        }

        // MLKit은 InputImage에 rotationDegrees를 넣으면 "회전 보정(upright)" 좌표계를 사용합니다.
        val imgW: Float
        val imgH: Float
        if (rotationDegrees == 90 || rotationDegrees == 270) {
            imgW = imageProxy.height.toFloat()
            imgH = imageProxy.width.toFloat()
        } else {
            imgW = imageProxy.width.toFloat()
            imgH = imageProxy.height.toFloat()
        }

        val viewToImgScale: Float = when (previewView.scaleType) {
            PreviewView.ScaleType.FIT_CENTER,
            PreviewView.ScaleType.FIT_START,
            PreviewView.ScaleType.FIT_END -> {
                min(viewW / imgW, viewH / imgH)
            }
            else -> {
                // FILL_* 계열
                max(viewW / imgW, viewH / imgH)
            }
        }

        val dispW = imgW * viewToImgScale
        val dispH = imgH * viewToImgScale
        val offsetX = (viewW - dispW) / 2f
        val offsetY = (viewH - dispH) / 2f

        val left = ((roiView.left - offsetX) / viewToImgScale).coerceIn(0f, imgW)
        val top = ((roiView.top - offsetY) / viewToImgScale).coerceIn(0f, imgH)
        val right = ((roiView.right - offsetX) / viewToImgScale).coerceIn(0f, imgW)
        val bottom = ((roiView.bottom - offsetY) / viewToImgScale).coerceIn(0f, imgH)

        return RectF(left, top, right, bottom)
    }

    private fun setScanningUi() {
        mrzOverlay.setGuideColor(COLOR_SCANNING)
        tvHint.text = "여권 하단 MRZ코드 또는\nH.Point 바코드를 박스 안에 맞춰주세요"
        tvSubHint.text = "※ 초점이 흐리면 조금 멀리 두거나 화면을 탭하세요."
    }

    private fun handleRetry(isBlurry: Boolean) {
        if (!shouldHandleAsyncCallback()) return
        val now = System.currentTimeMillis()
        if (now - lastUiFeedbackTimeMs < RETRY_INTERVAL_MS) return
        lastUiFeedbackTimeMs = now

        val msg = when {
            isBlurry -> "초점이 흐립니다.\n여권을 조금 멀리 두거나 화면을 탭해 초점을 맞춰주세요"
            flip180Enabled -> "여권 하단 MRZ코드 또는 H.Point 바코드를 박스 안에 맞춰주세요"
            else -> "여권 하단 MRZ코드 또는\nH.Point 바코드를 박스 안에 맞춰주세요"
        }

        runWhenUiAlive {
            mrzOverlay.setGuideColor(COLOR_ERROR)
            tvHint.text = msg

            mrzOverlay.postDelayed({
                if (shouldHandleAsyncCallback()) mrzOverlay.setGuideColor(COLOR_SCANNING)
            }, 220)
        }
    }

    /**
     * PDA 기기 해상도/비율에 맞춘 튜닝:
     * - Preview는 aspectRatio만 고정해 고해상도 선택 여지를 주고(선명도↑)
     * - Analysis는 "너무 크지 않은" 해상도로 고정해 속도/발열을 낮춥니다.
     * - 상/하단 UI safe inset 반영
     */
    private fun applyDeviceTuning() {
        mrzOverlay.setSafeInsets(
            topPx = topBar.height.toFloat(),
            bottomPx = bottomPanel.height.toFloat() + dpToPx(10f)
        )

        val profile = chooseAnalysisProfileFromCamera()
        if (profile != null) {
            analysisSize = profile.size
            targetAspectRatio = profile.aspectRatio
        } else {
            // fallback
            analysisSize = Size(1280, 720)
            targetAspectRatio = AspectRatio.RATIO_4_3
        }

        val portraitRatio = previewView.height.toFloat() / previewView.width.toFloat()
        val isTall = portraitRatio >= 2.0f

        val widthRatio = if (isSp60) 0.99f else 0.98f
        val heightRatio = if (isSp60) {
            if (targetAspectRatio == AspectRatio.RATIO_4_3) 0.38f else 0.34f
        } else {
            if (targetAspectRatio == AspectRatio.RATIO_4_3) 0.30f else 0.26f
        }
        val centerYRatio = if (isTall) {
            if (isSp60) 0.80f else 0.78f
        } else {
            0.74f
        }
        mrzOverlay.setGuideRatios(widthRatio, heightRatio, centerYRatio)

        // 가이드 박스 위치가 바뀌면(해상도/비율 튜닝) 데모 힌트도 함께 재배치
        mrzOverlay.post { positionDemoHintAboveGuide() }

        val ratio = max(analysisSize.width, analysisSize.height).toFloat() / min(analysisSize.width, analysisSize.height).toFloat()
        Log.d(
            TAG,
            "tuning: view=${previewView.width}x${previewView.height} portraitRatio=${String.format(Locale.US, "%.3f", portraitRatio)} " +
                "analysis=${analysisSize.width}x${analysisSize.height} ratio=${String.format(Locale.US, "%.3f", ratio)} " +
                "aspect=${if (targetAspectRatio == AspectRatio.RATIO_4_3) "4:3" else "16:9"} sp60=$isSp60"
        )
    }

    private data class AnalysisProfile(val size: Size, val aspectRatio: Int)

    /**
     * Camera2의 YUV_420_888 지원 해상도 목록에서 "너무 크지 않으면서" 선명도는 유지되는 분석용 Size를 선택합니다.
     *
     * - 4:3 센서가 대부분이라 4:3 후보를 우선
     * - 다만 PDA에서 MLKit이 너무 느려지는 걸 막기 위해 픽셀 수 상한을 둡니다
     */
    private fun chooseAnalysisProfileFromCamera(): AnalysisProfile? {
        return try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null

            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                val sizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: continue
                if (sizes.isEmpty()) continue

                // 후보 정리
                val candidates = sizes
                    .map { Size(it.width, it.height) }
                    .filter { s ->
                        val w = s.width
                        val h = s.height
                        val pixels = w.toLong() * h.toLong()
                        val longSide = max(w, h)
                        val shortSide = min(w, h)

                        // 너무 작으면 인식률이 급격히 떨어짐
                        if (longSide < 900 || shortSide < 600) return@filter false

                        // 너무 크면 속도가 느려짐(PDA에서 체감 큼)
                        pixels <= 950_000L && longSide <= 1280
                    }

                val pool = if (candidates.isNotEmpty()) candidates else sizes.map { Size(it.width, it.height) }

                fun ratioOf(s: Size): Float = max(s.width, s.height).toFloat() / min(s.width, s.height).toFloat()
                fun isNear(s: Size, target: Float): Boolean = abs(ratioOf(s) - target) <= 0.03f

                val fourThree = 4f / 3f
                val sixteenNine = 16f / 9f

                val best4 = pool
                    .filter { isNear(it, fourThree) }
                    .maxByOrNull { it.width.toLong() * it.height.toLong() }

                val best16 = pool
                    .filter { isNear(it, sixteenNine) }
                    .maxByOrNull { it.width.toLong() * it.height.toLong() }

                if (best4 != null) {
                    return AnalysisProfile(best4, AspectRatio.RATIO_4_3)
                }
                if (best16 != null) {
                    return AnalysisProfile(best16, AspectRatio.RATIO_16_9)
                }

                val bestAny = pool.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: pool.first()
                val r = ratioOf(bestAny)
                val aspect = if (abs(r - fourThree) <= abs(r - sixteenNine)) AspectRatio.RATIO_4_3 else AspectRatio.RATIO_16_9

                return AnalysisProfile(bestAny, aspect)
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Y plane(밝기) 기반으로 선명도(에지 강도)를 아주 간단히 추정합니다.
     * - 값이 낮으면 전체적으로 흐림(너무 가까워서 초점이 안 잡힘) 가능성이 큼
     */
    private fun estimateSharpnessFromYPlane(imageProxy: ImageProxy): Double {
        return try {
            val plane = imageProxy.planes[0]
            val buffer: ByteBuffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            val w = imageProxy.width
            val h = imageProxy.height
            if (w <= 0 || h <= 0) return 0.0

            // MRZ가 보통 화면 하단 쪽이므로, 하단 영역에 가중
            val startX = (w * 0.18f).toInt().coerceIn(0, w - 2)
            val endX = (w * 0.82f).toInt().coerceIn(startX + 2, w - 1)
            val startY = (h * 0.55f).toInt().coerceIn(0, h - 2)
            val endY = (h * 0.95f).toInt().coerceIn(startY + 2, h - 1)

            val step = max(2, min(w, h) / 180)

            var sum = 0L
            var cnt = 0L

            // buffer.get(index) random access
            for (y in startY until endY step step) {
                val row = y * rowStride
                for (x in startX until endX step step) {
                    val idx = row + x * pixelStride
                    val v = buffer.get(idx).toInt() and 0xFF
                    val vR = buffer.get(idx + pixelStride).toInt() and 0xFF
                    sum += abs(v - vR)
                    cnt++
                }
            }

            if (cnt == 0L) 0.0 else (sum.toDouble() / cnt.toDouble())
        } catch (_: Exception) {
            0.0
        }
    }

    // ------------------------------------------------------------
    // Demo hint (passport MRZ / H.Point barcode) - 이미지 기반 안내
    // ------------------------------------------------------------

    private fun startDemoHintCarousel() {
        if (!this::demoHintContainer.isInitialized) return
        if (demoHintCarouselRunning) return
        demoHintCarouselRunning = true
        demoHintContainer.removeCallbacks(demoHintCarouselRunnable)
        demoHintContainer.post(demoHintCarouselRunnable)
    }

    private fun stopDemoHintCarousel() {
        if (!this::demoHintContainer.isInitialized) return
        demoHintCarouselRunning = false
        demoHintContainer.removeCallbacks(demoHintCarouselRunnable)
    }

    private fun updateDemoHint(type: DemoHintType, animate: Boolean) {
        if (!this::demoHintContainer.isInitialized) return
        if (!this::demoHintCard.isInitialized) return
        if (!this::demoHintImage.isInitialized) return
        if (!this::demoHintTitle.isInitialized) return
        if (!this::demoHintSubtitle.isInitialized) return

        if (demoHintType == type && demoHintImage.drawable != null && animate) {
            // 이미 같은 타입이면 불필요한 깜빡임을 줄입니다.
            return
        }

        demoHintType = type

        val (resId, title, subtitle) = when (type) {
            DemoHintType.MRZ -> Triple(
                R.drawable.demo_passport_mrz,
                "여권 MRZ코드를 박스에 맞춰주세요",
                "여권을 평평하게 두고 하단을 박스에 맞추면 인식이 빨라요"
            )
            DemoHintType.HPOINT -> Triple(
                R.drawable.demo_phone_barcode,
                "H.Point 바코드를 박스에 맞춰주세요",
                "앱 화면의 바코드가 흔들리지 않게 맞춰주세요"
            )
        }

        demoHintContainer.visibility = View.VISIBLE

        if (!animate) {
            demoHintImage.setImageResource(resId)
            demoHintTitle.text = title
            demoHintSubtitle.text = subtitle
            demoHintCard.alpha = 1f
            return
        }

        demoHintCard.animate().cancel()
        demoHintCard.animate()
            .alpha(0f)
            .setDuration(120L)
            .withEndAction {
                demoHintImage.setImageResource(resId)
                demoHintTitle.text = title
                demoHintSubtitle.text = subtitle
                demoHintCard.animate().alpha(1f).setDuration(170L).start()
            }
            .start()
    }

    private fun positionDemoHintAboveGuide() {
        if (!this::demoHintContainer.isInitialized) return

        val guide = mrzOverlay.guideRect
        if (guide.width() <= 0f || guide.height() <= 0f) return

        if (demoHintContainer.height == 0) {
            demoHintContainer.post { positionDemoHintAboveGuide() }
            return
        }

        // SP60 화면에서 크롭 박스와 가이드 카드 사이 간격을 조금 더 확보(=카드를 더 위로 올림)
        val margin = dpToPx(80f)
        val desiredTop = guide.top - demoHintContainer.height - margin
        val minTop = topBar.bottom.toFloat() + dpToPx(6f)

        val top = max(minTop, desiredTop)

        // parent 좌표 기준으로 배치(translation 포함)
        demoHintContainer.y = top
    }


    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density

    private fun vibrateSuccess() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!vibrator.hasVibrator()) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(60)
            }
        } catch (_: Exception) {
        }
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun shouldHandleAsyncCallback(): Boolean {
        return isUiAlive && !isDestroyed && !isFinishing && !isFinished.get()
    }

    private fun runWhenUiAlive(action: () -> Unit) {
        if (!isUiAlive) return
        runOnUiThread {
            if (!isUiAlive || isDestroyed) return@runOnUiThread
            action()
        }
    }
}
