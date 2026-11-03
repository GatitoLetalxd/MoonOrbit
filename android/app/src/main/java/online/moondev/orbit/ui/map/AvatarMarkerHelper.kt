package online.moondev.orbit.ui.map

import android.content.Context
import android.graphics.*
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory

object AvatarMarkerHelper {

    fun createMarkerIcon(
        context: Context,
        displayName: String,
        avatarBitmap: Bitmap? = null,
        isSos: Boolean = false,
        isSelf: Boolean = false,
        status: String = "active"
    ): BitmapDescriptor {
        val density = context.resources.displayMetrics.density
        val sizePx = (46 * density).toInt()
        val pillHeightPx = (18 * density).toInt()
        val totalHeightPx = sizePx + pillHeightPx + (4 * density).toInt()
        val totalWidthPx = (76 * density).toInt()

        val bitmap = Bitmap.createBitmap(totalWidthPx, totalHeightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val borderColor = when {
            isSos -> 0xFFFB7185.toInt()              // DarkError Red
            isSelf -> 0xFF67E8F9.toInt()             // DarkSecondary
            status == "paused" -> 0xFFFBBF24.toInt() // DarkWarning
            else -> 0xFF22D3EE.toInt()               // DarkPrimary Cyan
        }

        // Draw Avatar Circle
        val cx = totalWidthPx / 2f
        val cy = sizePx / 2f
        val radius = (sizePx - (6 * density)) / 2f

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0D1524.toInt() // DarkSurface
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, radius, bgPaint)

        if (avatarBitmap != null) {
            val scaled = Bitmap.createScaledBitmap(avatarBitmap, (radius * 2).toInt(), (radius * 2).toInt(), true)
            val shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val imgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.shader = shader
            }
            canvas.drawCircle(cx, cy, radius, imgPaint)
        } else {
            // Draw Initial letter
            val initial = displayName.take(1).uppercase()
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (isSos) 0xFFFB7185.toInt() else 0xFF22D3EE.toInt()
                textSize = 18 * density
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            val textY = cy - ((textPaint.descent() + textPaint.ascent()) / 2)
            canvas.drawText(initial, cx, textY, textPaint)
        }

        // Draw Border
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = borderColor
            style = Paint.Style.STROKE
            strokeWidth = 3 * density
        }
        canvas.drawCircle(cx, cy, radius, borderPaint)

        // Draw Name Pill
        val firstName = if (isSos) "🚨 ${displayName.split(" ").first()}" else displayName.split(" ").first()
        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isSos) 0xFFFB7185.toInt() else 0xFFF8FAFC.toInt()
            textSize = 10 * density
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        val textWidth = namePaint.measureText(firstName)
        val pillWidth = (textWidth + (12 * density)).coerceAtLeast(36 * density)
        val pillRect = RectF(
            cx - pillWidth / 2f,
            sizePx.toFloat(),
            cx + pillWidth / 2f,
            sizePx + pillHeightPx.toFloat()
        )

        val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xE60D1524.toInt() // DarkSurface semi-opaque
            style = Paint.Style.FILL
        }
        val pillStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isSos) 0xFFFB7185.toInt() else 0xFF334155.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1 * density
        }
        canvas.drawRoundRect(pillRect, 6 * density, 6 * density, pillBgPaint)
        canvas.drawRoundRect(pillRect, 6 * density, 6 * density, pillStrokePaint)

        val pillTextY = pillRect.centerY() - ((namePaint.descent() + namePaint.ascent()) / 2)
        canvas.drawText(firstName, cx, pillTextY, namePaint)

        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }
}
