package com.hd.hdmobilepos.model

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.TypedArray
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import com.hd.hdmobilepos.R
import kotlin.math.abs
import kotlin.math.min

class MrzOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val DEFAULT_GUIDE_COLOR: Int = Color.WHITE
        private const val DEFAULT_MASK_COLOR: Int = 0x99000000.toInt() // 반투명 검정
    }

    private val guideRectInternal = RectF()

    private val maskPath = Path()

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = DEFAULT_GUIDE_COLOR
    }

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = DEFAULT_MASK_COLOR
    }

    private val scanLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = DEFAULT_GUIDE_COLOR
        alpha = 160
    }

    // --- Demo animation (MRZ / Barcode hint) ---
    private val demoStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = DEFAULT_GUIDE_COLOR
        alpha = 180
    }

    private val demoFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        alpha = 35
    }

    private val demoTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        textSize = dp(12f)
        alpha = 190
    }


    private val demoMrzPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        typeface = Typeface.MONOSPACE
        textSize = dp(12f)
        alpha = 210
    }

    private var guideColor: Int = DEFAULT_GUIDE_COLOR
    private var maskColor: Int = DEFAULT_MASK_COLOR

    private val cornerRadiusPx: Float = dp(14f)

    // --- Tuning (can be changed at runtime) ---
    // NOTE: These are ratios **within the usable area** (screen minus safe insets).
    private var guideWidthRatio: Float = 0.94f
    private var guideHeightRatio: Float = 0.22f
    private var guideCenterYRatio: Float = 0.72f

    // UI overlays(Top bar / Bottom panel) height in PX to avoid overlapping the guide rect.
    private var safeInsetTopPx: Float = 0f
    private var safeInsetBottomPx: Float = 0f

    // Scan line animation
    private var scanAnimator: ValueAnimator? = null
    private var scanLineY: Float = 0f

    private var demoAnimator: ValueAnimator? = null
    private var demoT: Float = 0f
    private var showDemoAnimation: Boolean = true

    init {
        initAttributes(context, attrs, defStyleAttr)
        applyColors()
    }

    private fun initAttributes(context: Context, attrs: AttributeSet?, defStyleAttr: Int) {
        if (attrs == null) return
        val a: TypedArray = context.obtainStyledAttributes(
            attrs,
            R.styleable.MrzOverlayView,
            defStyleAttr,
            0
        )

        guideColor = a.getColor(R.styleable.MrzOverlayView_guideColor, DEFAULT_GUIDE_COLOR)
        maskColor = a.getColor(R.styleable.MrzOverlayView_maskColor, DEFAULT_MASK_COLOR)

        a.recycle()
    }

    private fun applyColors() {
        boxPaint.color = guideColor
        scanLinePaint.color = guideColor
        demoStrokePaint.color = guideColor
        demoMrzPaint.color = guideColor
        demoTextPaint.color = guideColor
        maskPaint.color = maskColor
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        updateGuideRectInternal(w, h)
    }

    private fun updateGuideRectInternal(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return

        // 1) Safe area (avoid overlapping the top/bottom UI)
        val topSafe = safeInsetTopPx + dp(10f)
        val bottomSafe = (h.toFloat() - safeInsetBottomPx - dp(12f)).coerceAtLeast(topSafe + dp(120f))
        val usableH = (bottomSafe - topSafe).coerceAtLeast(dp(120f))

        // 2) Guide rect size
        val horizontalMargin = dp(18f)
        val maxBoxWidth = (w.toFloat() - horizontalMargin * 2f).coerceAtLeast(dp(120f))
        val boxWidth = (w * guideWidthRatio).coerceIn(dp(120f), maxBoxWidth)

        // MRZ는 "넓고 낮은" 박스가 좋지만, 기기별로 하단 패널이 큰 경우를 고려해 usableH 기준으로 잡습니다.
        val boxHeight = (usableH * guideHeightRatio).coerceIn(dp(90f), usableH * 0.55f)

        // 3) Y position inside usable area
        val centerY = topSafe + usableH * guideCenterYRatio
        val left = (w - boxWidth) / 2f
        var top = centerY - boxHeight / 2f
        var bottom = top + boxHeight

        // Clamp inside safe area
        if (top < topSafe) {
            top = topSafe
            bottom = top + boxHeight
        }
        if (bottom > bottomSafe) {
            bottom = bottomSafe
            top = bottom - boxHeight
        }

        val right = left + boxWidth
        guideRectInternal.set(left, top, right, bottom)

        rebuildMaskPath(w.toFloat(), h.toFloat())

        // 애니메이션 위치 초기화
        scanLineY = guideRectInternal.top
        invalidate()
    }

    private fun rebuildMaskPath(w: Float, h: Float) {
        maskPath.reset()
        // 전체 화면
        maskPath.addRect(0f, 0f, w, h, Path.Direction.CW)
        // 가이드 영역(구멍)
        maskPath.addRoundRect(guideRectInternal, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
        // 구멍을 뚫기 위해 even-odd
        maskPath.fillType = Path.FillType.EVEN_ODD
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1) 반투명 마스크 + cutout
        canvas.drawPath(maskPath, maskPaint)

        // 2) 가이드 박스
        canvas.drawRoundRect(guideRectInternal, cornerRadiusPx, cornerRadiusPx, boxPaint)

        // 3) 스캔 라인(선택)
        if (scanAnimator != null) {
            val pad = dp(10f)
            val y = scanLineY.coerceIn(guideRectInternal.top, guideRectInternal.bottom)
            canvas.drawLine(guideRectInternal.left + pad, y, guideRectInternal.right - pad, y, scanLinePaint)
        }

        // 4) 상단 데모 애니메이션(선택)
        drawDemoHint(canvas)
    }

    private fun drawDemoHint(canvas: Canvas) {
        if (!showDemoAnimation) return
        if (demoAnimator == null) return

        // 데모 영역: 상단 safe inset 아래 ~ 가이드 박스 위
        val areaTop = safeInsetTopPx + dp(18f)
        val areaBottom = guideRectInternal.top - dp(18f)
        val areaH = areaBottom - areaTop
        if (areaH < dp(54f)) return

        val centerX = width / 2f
        val centerY = areaTop + areaH * 0.55f

        val cardW = min(width * 0.62f, dp(240f))
        val cardH = dp(62f)

        val isMrz = demoT < 0.5f
        val p = if (isMrz) demoT * 2f else (demoT - 0.5f) * 2f

        // 삼각파 형태로 살짝 아래로 "쏙" 들어가는 모션
        val tri = 1f - abs(p - 0.5f) * 2f // 0..1
        val dy = tri * dp(10f)

        // 페이드 인/아웃
        val fadeIn = (p / 0.15f).coerceIn(0f, 1f)
        val fadeOut = ((1f - p) / 0.15f).coerceIn(0f, 1f)
        val alphaF = (fadeIn * fadeOut).coerceIn(0f, 1f)

        val strokeA = (180 * alphaF).toInt().coerceIn(0, 255)
        val fillA = (35 * alphaF).toInt().coerceIn(0, 255)
        val textA = (190 * alphaF).toInt().coerceIn(0, 255)
        demoStrokePaint.alpha = strokeA
        demoFillPaint.alpha = fillA
        demoTextPaint.alpha = textA
        demoMrzPaint.alpha = textA

        val left = centerX - cardW / 2f
        val top = centerY - cardH / 2f + dy
        val right = centerX + cardW / 2f
        val bottom = centerY + cardH / 2f + dy
        val cardRect = RectF(left, top, right, bottom)

        val r = dp(14f)
        canvas.drawRoundRect(cardRect, r, r, demoFillPaint)
        canvas.drawRoundRect(cardRect, r, r, demoStrokePaint)

        // 카드 내부 도형(간단한 MRZ/Barcode 모사)
        val pad = dp(12f)
        val innerLeft = cardRect.left + pad
        val innerRight = cardRect.right - pad
        val innerTop = cardRect.top + dp(14f)

        if (isMrz) {
            // MRZ: 실제 MRZ 코드처럼 보이도록 예시 문자열(모노스페이스)
            // NOTE: 실제 여권 데이터가 아니라 UI 가이드용 샘플입니다.
            // 사용자가 익숙한 형태(P<TWNABC<<<<<< …)로 보이도록 구성합니다.
            val mrz1 = "P<TWNABC<<DEF<<GHI<<<<<<<<<<<<<<<<<<<<"
            val mrz2 = "A12345678<1TWN9001018M3001012<<<<<<<<<<"

            val y1 = innerTop + dp(2f)
            val y2 = y1 + dp(16f)

            drawFittedText(canvas, mrz1, innerLeft, y1, innerRight - innerLeft, demoMrzPaint)
            drawFittedText(canvas, mrz2, innerLeft, y2, innerRight - innerLeft, demoMrzPaint)

            // 라벨
            canvas.drawText("MRZ", cardRect.left + pad, cardRect.bottom - dp(10f), demoTextPaint)
        } else {
            // Barcode: 세로 바 느낌
            val barTop = innerTop - dp(2f)
            val barBottom = barTop + dp(24f)
            var x = innerLeft
            var i = 0
            while (x < innerRight) {
                val w = if (i % 3 == 0) dp(3f) else dp(1.5f)
                canvas.drawLine(x, barTop, x, barBottom, demoStrokePaint)
                x += w + dp(2f)
                i++
            }

            canvas.drawText("H.Point", cardRect.left + pad, cardRect.bottom - dp(10f), demoTextPaint)
        }

        // 아래 방향 화살표(간단)
        val arrowY = cardRect.bottom + dp(6f)
        val arrowTo = (guideRectInternal.top - dp(10f)).coerceAtMost(arrowY + dp(26f))
        val arrowX = centerX
        if (arrowTo > arrowY + dp(8f)) {
            canvas.drawLine(arrowX, arrowY, arrowX, arrowTo, demoStrokePaint)
            // 작은 삼각형
            canvas.drawLine(arrowX - dp(6f), arrowTo - dp(6f), arrowX, arrowTo, demoStrokePaint)
            canvas.drawLine(arrowX + dp(6f), arrowTo - dp(6f), arrowX, arrowTo, demoStrokePaint)
        }
    }

    /** 외부에서 가이드 색상을 변경할 때 호출 */
    fun setGuideColor(color: Int) {
        guideColor = color
        applyColors()
        invalidate()
    }

    /**
     * (권장) 상단바/하단패널 등 UI 영역을 침범하지 않도록 safe inset(px)을 설정합니다.
     * 값 변경 시 즉시 guide rect가 재계산됩니다.
     */
    fun setSafeInsets(topPx: Float, bottomPx: Float) {
        safeInsetTopPx = topPx.coerceAtLeast(0f)
        safeInsetBottomPx = bottomPx.coerceAtLeast(0f)
        updateGuideRectInternal(width, height)
    }

    /**
     * 기기/카메라 비율에 따라 guide rect를 미세 튜닝할 때 사용합니다.
     * - widthRatio: 0.5 ~ 0.99
     * - heightRatio: 0.1 ~ 0.6
     * - centerYRatio: 0.0 ~ 1.0 (usable area 기준)
     */
    fun setGuideRatios(widthRatio: Float, heightRatio: Float, centerYRatio: Float) {
        guideWidthRatio = widthRatio.coerceIn(0.5f, 0.99f)
        guideHeightRatio = heightRatio.coerceIn(0.1f, 0.6f)
        guideCenterYRatio = centerYRatio.coerceIn(0.0f, 1.0f)
        updateGuideRectInternal(width, height)
    }

    /**
     * 가이드 영역 반환(뷰 좌표).
     * 기존 Java 코드와 호환되도록 property로 제공합니다.
     */
    val guideRect: RectF
        get() = guideRectInternal

    fun startScanAnimation() {
        if (scanAnimator != null) return
        if (guideRectInternal.width() <= 0f || guideRectInternal.height() <= 0f) return

        scanAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                scanLineY = guideRectInternal.top + (guideRectInternal.height() * t)
                invalidate()
            }
            start()
        }

        // 데모 애니메이션도 함께 시작
        startDemoAnimation()
    }

    fun stopScanAnimation() {
        scanAnimator?.cancel()
        scanAnimator = null

        stopDemoAnimation()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopScanAnimation()
    }

    fun setShowDemoAnimation(show: Boolean) {
        showDemoAnimation = show
        invalidate()
    }

    private fun startDemoAnimation() {
        if (!showDemoAnimation) return
        if (demoAnimator != null) return
        demoAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2600L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                demoT = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopDemoAnimation() {
        demoAnimator?.cancel()
        demoAnimator = null
    }

    private fun drawFittedText(canvas: Canvas, text: String, x: Float, y: Float, maxWidth: Float, paint: Paint) {
        if (text.isEmpty() || maxWidth <= 0f) return
        val originalSize = paint.textSize
        val w = paint.measureText(text)
        if (w > maxWidth && w > 0f) {
            val scaled = (originalSize * (maxWidth / w)).coerceIn(dp(8f), originalSize)
            paint.textSize = scaled
        }
        canvas.drawText(text, x, y, paint)
        paint.textSize = originalSize
    }

    private fun dp(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics
        )
    }
}
