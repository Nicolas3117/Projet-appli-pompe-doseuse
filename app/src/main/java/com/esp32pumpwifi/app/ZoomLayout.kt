package com.esp32pumpwifi.app

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.hypot

class ZoomLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var zoomEnabled = true
    private var doubleTapEnabled = true
    private var minScale = 1.0f
    private var maxScale = 3.0f

    private var scaleFactor = 1.0f
    private var translationXValue = 0.0f
    private var translationYValue = 0.0f

    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var lastX = 0.0f
    private var lastY = 0.0f
    private var isDragging = false
    private var isScaling = false
    private var consumedByDoubleTap = false

    init {
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.ZoomLayout, defStyleAttr, 0)
            zoomEnabled = a.getBoolean(R.styleable.ZoomLayout_zoomEnabled, true)
            doubleTapEnabled = a.getBoolean(R.styleable.ZoomLayout_doubleTapEnabled, true)
            minScale = a.getFloat(R.styleable.ZoomLayout_minScale, 1.0f)
            maxScale = a.getFloat(R.styleable.ZoomLayout_maxScale, 3.0f)
            a.recycle()
        }

        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isScaling = true
                return zoomEnabled
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!zoomEnabled) return false
                val newScale = (scaleFactor * detector.scaleFactor).coerceIn(minScale, maxScale)
                val scaleChange = newScale / scaleFactor
                scaleFactor = newScale

                val focusX = detector.focusX
                val focusY = detector.focusY
                translationXValue = (translationXValue - focusX) * scaleChange + focusX
                translationYValue = (translationYValue - focusY) * scaleChange + focusY
                clampTranslation()
                applyTransformations()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
                if (scaleFactor <= minScale) {
                    resetTransformations()
                }
            }
        })

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (zoomEnabled && doubleTapEnabled) {
                    consumedByDoubleTap = true
                    resetTransformations()
                    return true
                }
                return false
            }

            override fun onDown(e: MotionEvent): Boolean = true
        })
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        check(childCount == 1) { "ZoomLayout must host exactly one direct child." }
        getChildAt(0)?.let { child ->
            child.pivotX = 0.0f
            child.pivotY = 0.0f
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!zoomEnabled) {
            return super.dispatchTouchEvent(ev)
        }

        consumedByDoubleTap = false
        gestureDetector.onTouchEvent(ev)
        scaleDetector.onTouchEvent(ev)
        val childResult = super.dispatchTouchEvent(ev)
        return (consumedByDoubleTap || (scaleFactor > minScale && isDragging) || childResult)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!zoomEnabled) return super.onInterceptTouchEvent(ev)

        if (scaleDetector.isInProgress || ev.pointerCount > 1) {
            return true
        }

        if (scaleFactor > minScale) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activePointerId = ev.getPointerId(0)
                    lastX = ev.x
                    lastY = ev.y
                    isDragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = abs(ev.x - lastX)
                    val dy = abs(ev.y - lastY)
                    if (hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                        return true
                    }
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!zoomEnabled) return super.onTouchEvent(event)

        var handled = false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastX = event.x
                lastY = event.y
                isDragging = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                activePointerId = event.getPointerId(index)
                lastX = event.getX(index)
                lastY = event.getY(index)
            }
            MotionEvent.ACTION_MOVE -> {
                if (isScaling) {
                    handled = true
                } else if (scaleFactor > minScale) {
                    if (activePointerId == MotionEvent.INVALID_POINTER_ID && event.pointerCount > 0) {
                        activePointerId = event.getPointerId(0)
                        lastX = event.getX(0)
                        lastY = event.getY(0)
                    }

                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex >= 0) {
                        val x = event.getX(pointerIndex)
                        val y = event.getY(pointerIndex)
                        val dx = x - lastX
                        val dy = y - lastY

                        if (!isDragging && hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                            isDragging = true
                        }
                        if (isDragging) {
                            translationXValue += dx
                            translationYValue += dy
                            clampTranslation()
                            applyTransformations()
                            handled = true
                        }
                        lastX = x
                        lastY = y
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                if (pointerId == activePointerId) {
                    val newIndex = if (pointerIndex == 0) 1 else 0
                    if (newIndex < event.pointerCount) {
                        activePointerId = event.getPointerId(newIndex)
                        lastX = event.getX(newIndex)
                        lastY = event.getY(newIndex)
                    } else {
                        activePointerId = MotionEvent.INVALID_POINTER_ID
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                isDragging = false
                handled = isScaling
            }
        }

        return if (handled || isScaling) {
            true
        } else {
            super.onTouchEvent(event)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampTranslation()
        applyTransformations()
    }

    private fun applyTransformations() {
        val child = getChildAt(0) ?: return
        child.scaleX = scaleFactor
        child.scaleY = scaleFactor
        child.translationX = translationXValue
        child.translationY = translationYValue
    }

    private fun clampTranslation() {
        val child = getChildAt(0) ?: return
        if (scaleFactor <= minScale) {
            translationXValue = 0.0f
            translationYValue = 0.0f
            return
        }

        val parentWidth = width.toFloat()
        val parentHeight = height.toFloat()
        val scaledWidth = child.width * scaleFactor
        val scaledHeight = child.height * scaleFactor

        val minTranslationX = parentWidth - scaledWidth
        val minTranslationY = parentHeight - scaledHeight

        translationXValue = if (minTranslationX < 0.0f) {
            translationXValue.coerceIn(minTranslationX, 0.0f)
        } else {
            0.0f
        }
        translationYValue = if (minTranslationY < 0.0f) {
            translationYValue.coerceIn(minTranslationY, 0.0f)
        } else {
            0.0f
        }
    }

    private fun resetTransformations() {
        scaleFactor = 1.0f
        translationXValue = 0.0f
        translationYValue = 0.0f
        clampTranslation()
        applyTransformations()
    }
}
