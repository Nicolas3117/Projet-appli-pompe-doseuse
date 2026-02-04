package com.esp32pumpwifi.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

class PlanningView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ================= CONFIG (COMPACT) =================

    private val hourHeight = 170f
    private val columnWidth = 260f
    private val pumpCount = 4
    private val pumpWidth = columnWidth / pumpCount

    private val leftMargin = 120f
    private val topMargin = 90f
    private val headerHeight = 70f
    private val bottomMargin = 40f
    private val totalHours = 24

    // ================= ZOOM (PINCH - CONTINU, RAPIDE) =================

    private var scaleFactor = 1f
    private val minScale = 0.7f
    private val maxScale = 2.2f

    // ✅ Zoom rapide mais stable
    private val zoomDamping = 0.90f
    private val zoomJitterThreshold = 0.003f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val prev = scaleFactor

                val raw = detector.scaleFactor
                val adjusted = 1f + (raw - 1f) * zoomDamping

                // ✅ Ne bloque pas : neutralise juste les micro-variations
                val safeAdjusted =
                    if (abs(adjusted - 1f) < zoomJitterThreshold) 1f else adjusted

                scaleFactor = (scaleFactor * safeAdjusted).coerceIn(minScale, maxScale)

                if (scaleFactor != prev) {
                    postInvalidateOnAnimation()
                }
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                super.onScaleEnd(detector)
                // ✅ Ajuste la taille pour que les ScrollView suivent
                requestLayout()
            }
        }
    )

    // ================= PALETTE (SOMBRE INDUSTRIEL) =================

    private val bgColor = Color.parseColor("#12161C")
    private val stripeColor = Color.parseColor("#161D26")
    private val gridColor = Color.parseColor("#2A3440")
    private val textColor = Color.parseColor("#E6EDF3")
    private val textMuted = Color.parseColor("#A8B3BF")
    private val separatorStrong = Color.parseColor("#3A4756")
    private val separatorSoft = Color.parseColor("#2A3440")

    // ================= PEINTURES =================

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = bgColor
        style = Paint.Style.FILL
    }

    private val stripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = stripeColor
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gridColor
        strokeWidth = 1f
    }

    private val espSeparatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = separatorStrong
        strokeWidth = 2f
    }

    private val pumpSeparatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = separatorSoft
        strokeWidth = 1f
    }

    private val hourTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textMuted
        textSize = 24f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val espTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = 26f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val pumpHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textMuted
        textSize = 20f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val blockStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.BLACK
        alpha = 60
    }

    // ✅ Rouge uniquement quand overlap
    private val overlapStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#FF3B30")
    }

    private val blockTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 18f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    // ================= DONNÉES =================

    private var visibleEspIds: List<Long> = emptyList()

    private val blocks = mutableListOf<PlanningBlock>()
    private var blocksDirty = true

    data class PlanningBlock(
        val rect: RectF,
        val espId: Long,
        val espName: String,
        val pumpName: String,
        val pumpNum: Int,
        val startMsOfDay: Long,
        val endMsOfDay: Long,
        val durationMs: Long,
        val quantityMl: Float
    ) {
        var hasOverlap: Boolean = false
    }

    // ================= API =================

    fun setVisibleEspModules(ids: List<Long>) {
        visibleEspIds = ids
        blocksDirty = true
        requestLayout()
        invalidate()
    }

    fun refresh() {
        blocksDirty = true
        requestLayout()
        invalidate()
    }

    // ================= MESURE =================

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight =
            ((topMargin + bottomMargin + totalHours * hourHeight) * scaleFactor).toInt()

        val desiredWidth =
            ((leftMargin + max(1, visibleEspIds.size) * columnWidth) * scaleFactor).toInt()

        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    // ================= DESSIN =================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.save()
        canvas.scale(scaleFactor, scaleFactor)

        val viewW = width.toFloat() / scaleFactor
        val viewH = height.toFloat() / scaleFactor

        canvas.drawRect(0f, 0f, viewW, viewH, backgroundPaint)

        drawStripes(canvas, viewW)
        drawGrid(canvas, viewW)
        drawHours(canvas)
        drawEspSeparators(canvas)
        drawPumpSeparators(canvas)
        drawHeaders(canvas)

        if (blocksDirty) {
            rebuildBlocks()
        }

        drawBlocks(canvas)

        canvas.restore()
    }

    // ================= EN-TÊTES =================

    private fun drawHeaders(canvas: Canvas) {
        if (visibleEspIds.isEmpty()) return

        val prefsMain = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val modules = Esp32Manager.getAll(context)

        visibleEspIds.forEachIndexed { espIndex, espId ->
            val esp = modules.firstOrNull { it.id == espId } ?: return@forEachIndexed
            val espLeft = leftMargin + espIndex * columnWidth

            canvas.drawText(
                esp.displayName,
                espLeft + 12f,
                (topMargin - headerHeight) + 28f,
                espTitlePaint
            )

            for (pump in 1..pumpCount) {
                val pumpName =
                    prefsMain.getString("esp_${espId}_pump${pump}_name", "P$pump") ?: "P$pump"

                val shortName = shorten(pumpName, 7)
                val centerX = espLeft + (pump - 1) * pumpWidth + pumpWidth / 2f

                canvas.drawText(shortName, centerX, (topMargin - 18f), pumpHeaderPaint)
            }
        }
    }

    private fun shorten(name: String, maxLen: Int): String {
        val t = name.trim()
        if (t.length <= maxLen) return t
        return t.take(maxLen - 1) + "…"
    }

    // ================= ZÉBRAGE / GRILLE / HEURES =================

    private fun drawStripes(canvas: Canvas, viewW: Float) {
        for (h in 0 until totalHours) {
            if (h % 2 == 0) continue
            val yTop = topMargin + h * hourHeight
            val yBot = yTop + hourHeight
            canvas.drawRect(0f, yTop, viewW, yBot, stripePaint)
        }
    }

    private fun drawGrid(canvas: Canvas, viewW: Float) {
        canvas.drawLine(0f, topMargin, viewW, topMargin, espSeparatorPaint)
        for (h in 0..totalHours) {
            val y = topMargin + h * hourHeight
            canvas.drawLine(0f, y, viewW, y, gridPaint)
        }
    }

    private fun drawHours(canvas: Canvas) {
        for (h in 0..totalHours) {
            val y = topMargin + h * hourHeight
            canvas.drawText(
                String.format("%02d:00", h),
                10f,
                y + (hourTextPaint.textSize / 2f),
                hourTextPaint
            )
        }
    }

    private fun drawEspSeparators(canvas: Canvas) {
        val bottom = topMargin + totalHours * hourHeight
        visibleEspIds.forEachIndexed { index, _ ->
            val x = leftMargin + index * columnWidth
            canvas.drawLine(x, topMargin, x, bottom, espSeparatorPaint)
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

    // ================= REBUILD (LOURD) =================

    private fun rebuildBlocks() {
        blocks.clear()

        if (visibleEspIds.isEmpty()) {
            blocksDirty = false
            return
        }

        val prefsSchedules = context.getSharedPreferences("schedules", Context.MODE_PRIVATE)
        val prefsMain = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val modules = Esp32Manager.getAll(context)

        visibleEspIds.forEachIndexed { _, espId ->
            val esp = modules.firstOrNull { it.id == espId } ?: return@forEachIndexed
            val espIndex = visibleEspIds.indexOf(espId)
            val espLeft = leftMargin + espIndex * columnWidth

            for (pumpNum in 1..pumpCount) {

                val flow = prefsMain.getFloat("esp_${espId}_pump${pumpNum}_flow", 0f)
                if (flow <= 0f) continue

                val pumpName =
                    prefsMain.getString("esp_${espId}_pump${pumpNum}_name", "P$pumpNum")
                        ?: "P$pumpNum"

                val pumpLeft = espLeft + (pumpNum - 1) * pumpWidth

                val events = buildPumpEvents(
                    espId = espId,
                    pumpNum = pumpNum,
                    flow = flow,
                    prefsSchedules = prefsSchedules
                )

                events.forEach { event ->
                    val startMs = event.startMsOfDay
                    val endMs = event.endMsOfDay
                    if (startMs >= DAY_MS || endMs <= 0L) return@forEach

                    val startY =
                        topMargin + (startMs.toFloat() / MILLIS_PER_HOUR) * hourHeight

                    val height = heightForDuration(
                        durationMs = event.durationMs,
                        quantityMl = event.quantityMl
                    )

                    val rect = RectF(
                        pumpLeft + 6f,
                        startY,
                        pumpLeft + pumpWidth - 6f,
                        startY + height
                    )

                    blocks.add(
                        PlanningBlock(
                            rect = rect,
                            espId = espId,
                            espName = esp.displayName,
                            pumpName = pumpName,
                            pumpNum = pumpNum,
                            startMsOfDay = startMs,
                            endMsOfDay = endMs,
                            durationMs = event.durationMs,
                            quantityMl = event.quantityMl
                        )
                    )
                }
            }
        }

        computeOverlaps()
        blocksDirty = false
    }

    private data class PumpEvent(
        val startMsOfDay: Long,
        val endMsOfDay: Long,
        val durationMs: Long,
        val quantityMl: Float
    )

    /**
     * Durée prioritaire = ProgramStoreSynced (lignes 12 digits).
     * Fallback = durée déduite de (quantityMl / flow) * 1000.
     */
    private fun buildPumpEvents(
        espId: Long,
        pumpNum: Int,
        flow: Float,
        prefsSchedules: android.content.SharedPreferences
    ): List<PumpEvent> {
        val encodedLines = ProgramStoreSynced.loadEncodedLines(context, espId, pumpNum)
        if (encodedLines.isNotEmpty()) {
            return encodedLines.mapNotNull { line ->
                parseEncodedLine(line, flow)
            }
        }

        val json = prefsSchedules.getString("esp_${espId}_pump$pumpNum", null) ?: return emptyList()
        val schedules: List<PumpSchedule> = try {
            PumpScheduleJson.fromJson(json)
        } catch (_: Exception) {
            emptyList()
        }

        return schedules.mapNotNull { s ->
            if (!s.enabled) return@mapNotNull null
            val parts = s.time.split(":")
            if (parts.size != 2) return@mapNotNull null
            val hh = parts[0].toIntOrNull() ?: return@mapNotNull null
            val mm = parts[1].toIntOrNull() ?: return@mapNotNull null
            if (hh !in 0..23 || mm !in 0..59) return@mapNotNull null

            val durationMs = ((s.quantityMl / flow) * 1000f).roundToInt().toLong()
            if (durationMs <= 0L) return@mapNotNull null

            buildEvent(hh, mm, durationMs, s.quantityMl)
        }
    }

    private fun parseEncodedLine(line: String, flow: Float): PumpEvent? {
        if (line.length != 12) return null
        if (line[0] != '1') return null
        val hh = line.substring(2, 4).toIntOrNull() ?: return null
        val mm = line.substring(4, 6).toIntOrNull() ?: return null
        val durationMsRaw = line.substring(6, 12).toIntOrNull() ?: return null
        if (hh !in 0..23 || mm !in 0..59) return null
        if (durationMsRaw <= 0) return null

        val quantityMl = (durationMsRaw / 1000f) * flow
        return buildEvent(hh, mm, durationMsRaw.toLong(), quantityMl)
    }

    /**
     * Clamp à 24:00 + recalcul durée effective clampée.
     */
    private fun buildEvent(
        hh: Int,
        mm: Int,
        durationMs: Long,
        quantityMl: Float
    ): PumpEvent? {
        val startMs = ((hh * 3600L) + (mm * 60L)) * 1000L
        if (startMs >= DAY_MS) return null
        val endMs = (startMs + durationMs).coerceAtMost(DAY_MS)
        val effectiveDurationMs = endMs - startMs
        if (effectiveDurationMs <= 0L) return null

        return PumpEvent(
            startMsOfDay = startMs,
            endMsOfDay = endMs,
            durationMs = effectiveDurationMs,
            quantityMl = quantityMl
        )
    }

    /**
     * Hauteur = durée (référence temps) + léger boost selon volume (pour lisibilité).
     * Boost plafonné (≈ +35%) pour ne pas “tricher” sur la timeline.
     */
    private fun heightForDuration(durationMs: Long, quantityMl: Float): Float {
        val base = (durationMs.toFloat() / MILLIS_PER_HOUR) * hourHeight
        val minH = minBlockHeightPx()

        // Boost doux : 1ml ≈ +10%, 9ml ≈ +30%, cap ≈ +35%
        val boost = (sqrt(quantityMl.coerceAtLeast(0f)) / 10f).coerceAtMost(0.35f)
        val factor = 1f + boost

        return max(base * factor, minH)
    }

    private fun minBlockHeightPx(): Float = 6f * resources.displayMetrics.density

    // ================= DESSIN BLOCS (LÉGER) =================

    private fun drawBlocks(canvas: Canvas) {
        for (b in blocks) {
            blockPaint.color = pumpColor(b.pumpNum)

            canvas.drawRoundRect(b.rect, 10f, 10f, blockPaint)
            canvas.drawRoundRect(b.rect, 10f, 10f, blockStrokePaint)

            // ✅ Contour rouge UNIQUEMENT si overlap
            if (b.hasOverlap) {
                canvas.drawRoundRect(b.rect, 10f, 10f, overlapStrokePaint)
            }

            drawBlockLabels(canvas, b)
        }
    }

    /**
     * Label = volume (sans “mL” si tu veux alléger).
     * On évite d’écrire si le bloc est trop petit.
     */
    private fun drawBlockLabels(canvas: Canvas, block: PlanningBlock) {
        val height = block.rect.height()
        if (height < 16f) return // trop petit → pas de texte

        val volumeText =
            QuantityInputUtils.formatQuantityMl((block.quantityMl * 10f).roundToInt())

        val textY = block.rect.centerY() + (blockTextPaint.textSize / 3f)
        canvas.drawText(volumeText, block.rect.centerX(), textY, blockTextPaint)
    }

    // ================= OVERLAPS =================

    private fun computeOverlaps() {
        blocks.forEach { it.hasOverlap = false }

        for (i in 0 until blocks.size) {
            val a = blocks[i]
            for (j in i + 1 until blocks.size) {
                val b = blocks[j]
                if (a.startMsOfDay < b.endMsOfDay && b.startMsOfDay < a.endMsOfDay) {
                    a.hasOverlap = true
                    b.hasOverlap = true
                }
            }
        }
    }

    // ================= TOUCH =================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Important: laisse le détecteur gérer le pinch
        scaleDetector.onTouchEvent(event)

        // pendant un pinch, on ne fait rien d'autre
        if (scaleDetector.isInProgress) return true

        if (event.action == MotionEvent.ACTION_DOWN) {
            val touchX = event.x / scaleFactor
            val touchY = event.y / scaleFactor

            blocks.firstOrNull { it.rect.contains(touchX, touchY) }?.let {
                showBlockDetails(it)
                return true
            }
        }
        return true
    }

    // ================= DIALOG =================

    private fun showBlockDetails(block: PlanningBlock) {
        AlertDialog.Builder(context)
            .setTitle("Détail distribution")
            .setMessage(
                """
                Module : ${block.espName}
                Pompe : ${block.pumpName}
                Début : ${formatTime(block.startMsOfDay)}
                Fin : ${formatTime(block.endMsOfDay)}
                Volume : ${QuantityInputUtils.formatQuantityMl((block.quantityMl * 10f).roundToInt())} ml
                """.trimIndent()
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun formatTime(msOfDay: Long): String {
        if (msOfDay >= DAY_MS) return "24:00"
        val totalSeconds = (msOfDay / 1000L).toInt()
        val hh = totalSeconds / 3600
        val mm = (totalSeconds % 3600) / 60
        return String.format(Locale.getDefault(), "%02d:%02d", hh, mm)
    }

    // ================= COULEURS =================

    private fun pumpColor(pump: Int): Int =
        when (pump) {
            1 -> Color.parseColor("#2E7D32")
            2 -> Color.parseColor("#1565C0")
            3 -> Color.parseColor("#EF6C00")
            4 -> Color.parseColor("#6A1B9A") // ✅ violet propre
            else -> Color.DKGRAY
        }
}

private const val MILLIS_PER_HOUR = 3600000f
private const val DAY_MS = 24 * 3600L * 1000L
