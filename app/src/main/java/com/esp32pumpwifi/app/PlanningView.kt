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

    // ================= CONFIG (GANTT HORIZONTAL) =================

    private val totalHours = 24
    private val pumpCount = 4

    private val hourWidth = 360f          // ✅ cases horaires plus larges (1h = 360px avant zoom)
    private val laneHeight = 70f
    private val moduleGap = 26f

    private val leftMargin = 190f         // place pour noms modules/pompes
    private val topMargin = 90f
    private val headerHeight = 60f
    private val bottomMargin = 40f
    private val rightMargin = 40f

    // ✅ Hauteur fixe des barres (tu voulais hauteur fixe)
    private val barHeight = 28f

    // NOTES: paliers 0-250ms(minW..+6dp), 250-1000ms(+6..+14dp), 1-3s(+14..+22dp), 3-8s(+22..+34dp),
    // 8-20s(+34..+46dp), 20-60s(+46..+60dp), >60s compression sqrt vers maxW; minW=6dp; maxW=min(hourWidth*0.25,120dp).
    // boost volume max +4dp; hauteur fixe barHeight confirmée.
    // Diagnostic: largeur calculée dans widthForDose(), utilisée dans rebuildBlocks() pour width des barres.
    // Ancien min/max/boost trop élevés => durées courtes devenaient visuellement énormes.
    private fun widthForDose(durationMs: Long, quantityMl: Float): Float {
        val density = resources.displayMetrics.density
        val minW = 6f * density
        val maxW = minOf(hourWidth * 0.25f, 120f * density)

        val t = durationMs.coerceAtLeast(0L).toFloat()
        val t0 = 250f
        val t1 = 1000f
        val t2 = 3000f
        val t3 = 8000f
        val t4 = 20000f
        val t5 = 60000f

        val stepA = minW + 6f * density
        val stepB = minW + 14f * density
        val stepC = minW + 22f * density
        val stepD = minW + 34f * density
        val stepE = minW + 46f * density
        val stepF = minW + 60f * density

        val base = when {
            t <= t0 -> lerp(minW, stepA, t / t0)
            t <= t1 -> lerp(stepA, stepB, (t - t0) / (t1 - t0))
            t <= t2 -> lerp(stepB, stepC, (t - t1) / (t2 - t1))
            t <= t3 -> lerp(stepC, stepD, (t - t2) / (t3 - t2))
            t <= t4 -> lerp(stepD, stepE, (t - t3) / (t4 - t3))
            t <= t5 -> lerp(stepE, stepF, (t - t4) / (t5 - t4))
            else -> {
                val ratio = ((t - t5) / t5).coerceIn(0f, 4f)
                val soft = sqrt(ratio / 4f)
                stepF + (maxW - stepF) * soft
            }
        }

        val q = quantityMl.coerceAtLeast(0f)
        val volumeBoost = (sqrt(q / 50f) * (4f * density)).coerceAtMost(4f * density)

        return (base + volumeBoost).coerceIn(minW, maxW)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

    // ================= ZOOM (PINCH - CONTINU, RAPIDE) =================

    private var scaleFactor = 1f
    private val minScale = 0.7f
    private val maxScale = 2.2f

    private val zoomDamping = 0.90f
    private val zoomJitterThreshold = 0.003f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val prev = scaleFactor

                val raw = detector.scaleFactor
                val adjusted = 1f + (raw - 1f) * zoomDamping

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

    private val strongSepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = separatorStrong
        strokeWidth = 2f
    }

    private val softSepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = separatorSoft
        strokeWidth = 1f
    }

    private val hourTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textMuted
        textSize = 20f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val espTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = 24f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val pumpHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textMuted
        textSize = 18f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
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
    )

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
        val moduleCount = max(1, visibleEspIds.size)

        val contentHeight =
            topMargin + (moduleCount * (pumpCount * laneHeight + moduleGap)) - moduleGap + bottomMargin

        val contentWidth = leftMargin + timelineWidthPx() + rightMargin

        val desiredHeight = (contentHeight * scaleFactor).toInt()
        val desiredWidth = (contentWidth * scaleFactor).toInt()

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
        drawGrid(canvas)
        drawHourLabels(canvas)
        drawModuleSeparators(canvas, viewW)
        drawLaneSeparators(canvas, viewW)
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
            val moduleTop = moduleTop(espIndex)

            canvas.drawText(
                esp.displayName,
                12f,
                moduleTop + 26f,
                espTitlePaint
            )

            for (pump in 1..pumpCount) {
                val pumpName =
                    prefsMain.getString("esp_${espId}_pump${pump}_name", "P$pump") ?: "P$pump"
                val shortName = shorten(pumpName, 10)

                val laneTop = moduleTop + (pump - 1) * laneHeight
                val textY = laneTop + (laneHeight * 0.68f)

                canvas.drawText(shortName, leftMargin - 12f, textY, pumpHeaderPaint)
            }
        }
    }

    private fun shorten(name: String, maxLen: Int): String {
        val t = name.trim()
        if (t.length <= maxLen) return t
        return t.take(maxLen - 1) + "…"
    }

    // ================= GRILLE =================

    private fun drawStripes(canvas: Canvas, viewW: Float) {
        val moduleCount = max(1, visibleEspIds.size)
        for (moduleIndex in 0 until moduleCount) {
            val mt = moduleTop(moduleIndex)
            for (laneIndex in 0 until pumpCount) {
                val laneTop = mt + laneIndex * laneHeight
                if ((moduleIndex * pumpCount + laneIndex) % 2 == 1) {
                    canvas.drawRect(0f, laneTop, viewW, laneTop + laneHeight, stripePaint)
                }
            }
        }
    }

    private fun drawGrid(canvas: Canvas) {
        val bottom = contentBottom()
        // vertical hour lines
        for (h in 0..totalHours) {
            val x = leftMargin + h * hourWidth
            canvas.drawLine(x, topMargin, x, bottom, gridPaint)
        }
        // top baseline
        canvas.drawLine(0f, topMargin, leftMargin + timelineWidthPx(), topMargin, strongSepPaint)
    }

    private fun drawHourLabels(canvas: Canvas) {
        // ✅ toutes les heures (pas une heure sur deux)
        for (h in 0..totalHours) {
            val x = leftMargin + h * hourWidth
            canvas.drawText(
                String.format("%02d:00", h),
                x + 6f,
                topMargin - (headerHeight / 2f),
                hourTextPaint
            )
        }
    }

    private fun drawModuleSeparators(canvas: Canvas, viewW: Float) {
        val moduleCount = max(1, visibleEspIds.size)
        for (index in 0..moduleCount) {
            val y = moduleTop(index) - if (index == 0) 0f else moduleGap
            canvas.drawLine(0f, y, viewW, y, strongSepPaint)
        }
    }

    private fun drawLaneSeparators(canvas: Canvas, viewW: Float) {
        val moduleCount = max(1, visibleEspIds.size)
        for (moduleIndex in 0 until moduleCount) {
            val mt = moduleTop(moduleIndex)
            for (lane in 1 until pumpCount) {
                val y = mt + lane * laneHeight
                canvas.drawLine(0f, y, viewW, y, softSepPaint)
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

        visibleEspIds.forEachIndexed { espIndex, espId ->
            val esp = modules.firstOrNull { it.id == espId } ?: return@forEachIndexed
            val moduleTop = moduleTop(espIndex)

            for (pumpNum in 1..pumpCount) {

                val flow = prefsMain.getFloat("esp_${espId}_pump${pumpNum}_flow", 0f)
                if (flow <= 0f) continue

                val pumpName =
                    prefsMain.getString("esp_${espId}_pump${pumpNum}_name", "P$pumpNum")
                        ?: "P$pumpNum"

                val laneTop = moduleTop + (pumpNum - 1) * laneHeight
                val laneCenterY = laneTop + laneHeight / 2f

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

                    val startX =
                        leftMargin + (startMs.toFloat() / MILLIS_PER_HOUR) * hourWidth

                    val width = widthForDose(event.durationMs, event.quantityMl)

                    val maxRight = leftMargin + timelineWidthPx()
                    val rawRight = (startX + width).coerceAtMost(maxRight)

                    val left = (startX + 2f).coerceAtMost(rawRight - 1f)
                    val right = max(rawRight - 2f, left + 1f)

                    val top = laneCenterY - (barHeight / 2f)
                    val rect = RectF(left, top, right, top + barHeight)

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

        blocksDirty = false
    }

    private data class PumpEvent(
        val startMsOfDay: Long,
        val endMsOfDay: Long,
        val durationMs: Long,
        val quantityMl: Float
    )

    private fun buildPumpEvents(
        espId: Long,
        pumpNum: Int,
        flow: Float,
        prefsSchedules: android.content.SharedPreferences
    ): List<PumpEvent> {
        // priorité : lignes synchronisées (durée réelle)
        val encodedLines = ProgramStoreSynced.loadEncodedLines(context, espId, pumpNum)
        if (encodedLines.isNotEmpty()) {
            return encodedLines.mapNotNull { line ->
                parseEncodedLine(line, flow)
            }
        }

        // fallback : schedules JSON
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

            val volumeMl = s.quantityMl
            val durationMs = ((volumeMl / flow) * 1000f).roundToInt().toLong()
            if (durationMs <= 0L) return@mapNotNull null

            buildEvent(hh, mm, durationMs, volumeMl)
        }
    }

    private fun parseEncodedLine(line: String, flow: Float): PumpEvent? {
        if (line.length != 12) return null
        if (line[0] != '1') return null

        val hh = line.substring(2, 4).toIntOrNull() ?: return null
        val mm = line.substring(4, 6).toIntOrNull() ?: return null
        val durationMs = line.substring(6, 12).toIntOrNull() ?: return null

        if (hh !in 0..23 || mm !in 0..59) return null
        if (durationMs <= 0) return null

        val quantityMl = (durationMs / 1000f) * flow
        return buildEvent(hh, mm, durationMs.toLong(), quantityMl)
    }

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

    // ================= DESSIN BLOCS =================

    private fun drawBlocks(canvas: Canvas) {
        for (b in blocks) {
            blockPaint.color = pumpColor(b.pumpNum)

            canvas.drawRoundRect(b.rect, 10f, 10f, blockPaint)
            canvas.drawRoundRect(b.rect, 10f, 10f, blockStrokePaint)

            drawBlockLabel(canvas, b)
        }
    }

    // ✅ texte volume (sans unité si pas la place, avec "mL" si y'a la place)
    private fun drawBlockLabel(canvas: Canvas, block: PlanningBlock) {
        val w = block.rect.width()
        val h = block.rect.height()

        // NOTE: les dimensions des barres sont déjà en px "bruts" (barHeight = 28f).
        // L'ancien minH utilisait dp (12f * density), ce qui dépassait souvent 28px
        // sur écrans denses (x3 => 36px) -> condition toujours vraie, texte jamais dessiné.
        val minW = 14f
        val minH = 12f
        if (w < minW || h < minH) return

        val padding = 6f
        val maxTextWidth = (w - padding * 2f).coerceAtLeast(0f)

        val quantityMl = if (block.quantityMl.isFinite()) block.quantityMl else 0f
        val baseValue = if (quantityMl < 10f) {
            String.format(Locale.getDefault(), "%.1f", quantityMl)
        } else {
            String.format(Locale.getDefault(), "%.0f", quantityMl)
        }

        var text = baseValue
        val withUnit = "$baseValue mL"

        val previousTextSize = blockTextPaint.textSize
        val maxTextHeight = h * 0.7f
        if (maxTextHeight < previousTextSize) {
            blockTextPaint.textSize = maxTextHeight.coerceAtLeast(10f)
        }

        // si on a la place, on met l'unité
        if (blockTextPaint.measureText(withUnit) <= maxTextWidth && w >= 55f) {
            text = withUnit
        } else {
            // sinon sans unité
            text = baseValue
            // si ça dépasse encore (très rare), on raccourcit
            if (blockTextPaint.measureText(text) > maxTextWidth) {
                text = String.format(Locale.getDefault(), "%.1f", quantityMl)
            }
        }

        val textY = block.rect.centerY() + (blockTextPaint.textSize / 3f)
        canvas.drawText(text, block.rect.centerX(), textY, blockTextPaint)
        blockTextPaint.textSize = previousTextSize
    }

    // ================= TOUCH =================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
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
                Pompe : ${block.pumpName} (P${block.pumpNum})
                Début : ${formatTime(block.startMsOfDay)}
                Fin : ${formatTime(block.endMsOfDay)}
                Durée : ${block.durationMs} ms
                Volume : ${QuantityInputUtils.formatQuantityMl((block.quantityMl * 10f).roundToInt())} mL
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

    // ================= HELPERS LAYOUT =================

    private fun moduleTop(index: Int): Float =
        topMargin + index * (pumpCount * laneHeight + moduleGap)

    private fun contentBottom(): Float {
        val moduleCount = max(1, visibleEspIds.size)
        return topMargin + moduleCount * (pumpCount * laneHeight + moduleGap) - moduleGap
    }

    private fun timelineWidthPx(): Float = totalHours * hourWidth

    // ================= COULEURS =================

    private fun pumpColor(pump: Int): Int =
        when (pump) {
            1 -> Color.parseColor("#2E7D32") // vert
            2 -> Color.parseColor("#1565C0") // bleu
            3 -> Color.parseColor("#EF6C00") // orange
            4 -> Color.parseColor("#6A1B9A") // ✅ violet (pas de liseré orange)
            else -> Color.DKGRAY
        }
}

private const val MILLIS_PER_HOUR = 3600000f
private const val DAY_MS = 24 * 3600L * 1000L
