package com.esp32pumpwifi.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.math.max

class PlanningView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ================= CONFIG =================

    private val hourHeight = 320f

    private val columnWidth = 280f
    private val pumpCount = 4
    private val pumpWidth = columnWidth / pumpCount

    private val leftMargin = 160f
    private val topMargin = 70f
    private val bottomMargin = 60f
    private val totalHours = 24

    // ✅ Barres plus visibles mais toujours proportionnées au volume
    private val volumeScale = 1.6f

    // ================= PEINTURES =================

    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#EEEEEE")
    }

    private val gridPaint = Paint().apply {
        color = Color.GRAY
        strokeWidth = 1f
        isAntiAlias = true
    }

    private val espSeparatorPaint = Paint().apply {
        color = Color.DKGRAY
        strokeWidth = 1f
    }

    private val pumpSeparatorPaint = Paint().apply {
        color = Color.parseColor("#BDBDBD")
        strokeWidth = 1f
    }

    private val hourTextPaint = Paint().apply {
        color = Color.DKGRAY
        textSize = 34f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }

    private val espTitlePaint = Paint().apply {
        color = Color.DKGRAY
        textSize = 36f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }

    private val blockPaint = Paint().apply {
        isAntiAlias = true
    }

    // ================= DONNÉES =================

    private val gson = Gson()
    private var visibleEspIds: List<Long> = emptyList()

    private val blocks = mutableListOf<PlanningBlock>()

    data class PlanningBlock(
        val rect: RectF,
        val espName: String,
        val pumpName: String,
        val time: String,
        val quantity: Float
    )

    // ================= API =================

    fun setVisibleEspModules(ids: List<Long>) {
        visibleEspIds = ids
        requestLayout()
        invalidate()
    }

    // ================= MESURE =================

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {

        val desiredHeight =
            (topMargin + bottomMargin + totalHours * hourHeight).toInt()

        val desiredWidth =
            (leftMargin + max(1, visibleEspIds.size) * columnWidth).toInt()

        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    // ================= DESSIN =================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        blocks.clear()

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        drawGrid(canvas)
        drawEspSeparators(canvas)
        drawPumpSeparators(canvas)
        drawHours(canvas)
        drawPlanning(canvas)
    }

    // ================= HEURES =================

    private fun drawHours(canvas: Canvas) {
        for (h in 0..totalHours) {
            val y = topMargin + h * hourHeight
            canvas.drawText(
                String.format("%02d:00", h),
                12f,
                y + hourTextPaint.textSize / 2,
                hourTextPaint
            )
        }
    }

    private fun drawGrid(canvas: Canvas) {
        for (h in 0..totalHours) {
            val y = topMargin + h * hourHeight
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
        }
    }

    private fun drawEspSeparators(canvas: Canvas) {
        visibleEspIds.forEachIndexed { index, _ ->
            val x = leftMargin + index * columnWidth
            canvas.drawLine(x, topMargin, x, height.toFloat(), espSeparatorPaint)
        }
    }

    private fun drawPumpSeparators(canvas: Canvas) {
        val bottom = topMargin + totalHours * hourHeight
        visibleEspIds.forEachIndexed { espIndex, _ ->
            val espLeft = leftMargin + espIndex * columnWidth
            for (p in 1 until pumpCount) {
                val x = espLeft + p * pumpWidth
                canvas.drawLine(x, topMargin, x, bottom, pumpSeparatorPaint)
            }
        }
    }

    // ================= PLANNING =================

    private fun drawPlanning(canvas: Canvas) {

        if (visibleEspIds.isEmpty()) return

        val prefsSchedules =
            context.getSharedPreferences("schedules", Context.MODE_PRIVATE)

        val prefsMain =
            context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

        val modules = Esp32Manager.getAll(context)

        visibleEspIds.forEachIndexed { espIndex, espId ->

            val esp = modules.firstOrNull { it.id == espId }
                ?: return@forEachIndexed

            val espLeft = leftMargin + espIndex * columnWidth

            canvas.drawText(
                esp.displayName,
                espLeft + 16f,
                topMargin - 22f,
                espTitlePaint
            )

            for (pump in 1..4) {

                val json =
                    prefsSchedules.getString("esp_${espId}_pump$pump", null)
                        ?: continue

                val schedules: List<PumpSchedule> = try {
                    val type = object : TypeToken<List<PumpSchedule>>() {}.type
                    gson.fromJson(json, type) ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }

                val flow =
                    prefsMain.getFloat("esp_${espId}_pump${pump}_flow", 0f)

                if (flow <= 0f) continue

                val pumpName =
                    prefsMain.getString(
                        "esp_${espId}_pump${pump}_name",
                        "P$pump"
                    ) ?: "P$pump"

                val pumpLeft = espLeft + (pump - 1) * pumpWidth

                schedules.forEach { s ->

                    if (!s.enabled) return@forEach

                    val parts = s.time.split(":")
                    if (parts.size != 2) return@forEach

                    val hh = parts[0].toIntOrNull() ?: return@forEach
                    val mm = parts[1].toIntOrNull() ?: return@forEach

                    val quantityF = s.quantity.toFloat()

                    // ✅ Hauteur proportionnelle au volume + amplification légère
                    val height = max(
                        blockThickness(quantityF),
                        blockThickness(quantityF) * volumeScale
                    )

                    val startY =
                        topMargin +
                                hh * hourHeight +
                                (mm.toFloat() / 60f) * hourHeight

                    val rect = RectF(
                        pumpLeft + 6f,
                        startY,
                        pumpLeft + pumpWidth - 6f,
                        startY + height
                    )

                    blockPaint.color = pumpColor(pump)
                    canvas.drawRoundRect(rect, 10f, 10f, blockPaint)

                    blocks.add(
                        PlanningBlock(
                            rect = rect,
                            espName = esp.displayName,
                            pumpName = pumpName,
                            time = s.time,
                            quantity = quantityF
                        )
                    )
                }
            }
        }
    }

    // ================= TOUCH =================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            blocks.firstOrNull { it.rect.contains(event.x, event.y) }?.let {
                showBlockDetails(it)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // ================= DIALOG =================

    private fun showBlockDetails(block: PlanningBlock) {
        AlertDialog.Builder(context)
            .setTitle("Détail distribution")
            .setMessage(
                """
                Module : ${block.espName}
                Pompe : ${block.pumpName}
                Heure : ${block.time}
                Quantité : ${block.quantity} ml
                """.trimIndent()
            )
            .setPositiveButton("OK", null)
            .show()
    }

    // ================= ÉPAISSEUR =================

    private fun blockThickness(quantity: Float): Float =
        when {
            quantity <= 1f -> 8f
            quantity <= 5f -> 14f
            quantity <= 10f -> 22f
            else -> 30f
        }

    // ================= COULEURS =================

    private fun pumpColor(pump: Int): Int =
        when (pump) {
            1 -> Color.parseColor("#4CAF50")
            2 -> Color.parseColor("#2196F3")
            3 -> Color.parseColor("#FF9800")
            4 -> Color.parseColor("#9C27B0")
            else -> Color.DKGRAY
        }
}
