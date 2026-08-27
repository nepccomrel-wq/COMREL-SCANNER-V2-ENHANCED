package com.comrel.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View

/** Live document boundary overlay drawn over the CameraX preview. */
class DocumentEdgeOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x2200E676
    }

    private var corners: List<PointF> = emptyList()
    private var detected = false

    fun setCorners(points: List<PointF>) {
        corners = points
        detected = points.size == 4
        invalidate()
    }

    fun clearCorners() {
        corners = emptyList()
        detected = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (corners.size != 4) return

        val path = Path().apply {
            moveTo(corners[0].x, corners[0].y)
            for (i in 1 until corners.size) lineTo(corners[i].x, corners[i].y)
            close()
        }

        fillPaint.color = if (detected) 0x18FFFF00 else 0x00000000
        canvas.drawPath(path, fillPaint)

        paint.color = 0xFFFFFF00.toInt()
        canvas.drawPath(path, paint)

        val dotRadius = 6f * resources.displayMetrics.density
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFFFFFFFF.toInt()
        }
        corners.forEach { canvas.drawCircle(it.x, it.y, dotRadius, dotPaint) }
    }
}
