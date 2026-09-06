package com.cheatervpnapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class TrafficRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        color = ContextCompat.getColor(context, R.color.surface_variant)
    }

    private val rxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        color = ContextCompat.getColor(context, R.color.success)
    }

    private val txPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        color = ContextCompat.getColor(context, R.color.primary)
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        color = ContextCompat.getColor(context, R.color.on_surface)
    }

    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.text_secondary)
    }

    private val rect = RectF()
    private var ringStroke = 0f
    private var rxBytes = 0f
    private var txBytes = 0f
    private var centerTitle = ""
    private var centerSubtitle = ""

    init {
        ringStroke = 18f * resources.displayMetrics.density
        titlePaint.textSize = 22f * resources.displayMetrics.density
        subtitlePaint.textSize = 12f * resources.displayMetrics.density
    }

    fun setData(rx: Long, tx: Long, centerTitle: String, centerSubtitle: String) {
        rxBytes = rx.toFloat()
        txBytes = tx.toFloat()
        this.centerTitle = centerTitle
        this.centerSubtitle = centerSubtitle
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val half = ringStroke / 2f
        rect.set(half + 2f, half + 2f, width - half - 2f, height - half - 2f)

        canvas.drawArc(rect, -90f, 360f, false, trackPaint)

        val total = rxBytes + txBytes
        if (total > 0f) {
            val rxSweep = rxBytes / total * 360f
            val txSweep = txBytes / total * 360f
            canvas.drawArc(rect, -90f, rxSweep, false, rxPaint)
            canvas.drawArc(rect, -90f + rxSweep, txSweep, false, txPaint)
        }

        val cy = height / 2f
        canvas.drawText(centerTitle, width / 2f, cy, titlePaint)
        canvas.drawText(centerSubtitle, width / 2f, cy + subtitlePaint.textSize, subtitlePaint)
    }
}