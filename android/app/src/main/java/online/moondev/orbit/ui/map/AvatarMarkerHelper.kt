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

        // 1. Name Pill Dimensions (At the top)
        val firstName = if (isSos) "SOS • ${displayName.split(" ").first()}" else displayName.split(" ").first()
        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isSos) 0xFFFB7185.toInt() else 0xFFF8FAFC.toInt()
            textSize = 10.5f * density
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val textWidth = namePaint.measureText(firstName)
        val pillWidth = (textWidth + (14 * density)).coerceAtLeast(42 * density)
        val pillHeight = 18 * density
        val pillMarginBottom = 4 * density

        // 2. Avatar Globe Dimensions (In the middle)
        val globeDiameter = 48 * density
        val radius = globeDiameter / 2f

        // 3. Tip / Piquito Dimensions (At the bottom)
        val tipHeight = 8 * density
        val tipHalfWidth = 6.5f * density

        // 4. Total Canvas Bounds
        val totalWidth = (maxOf(pillWidth, globeDiameter + (12 * density))).toInt()
        val totalHeight = (pillHeight + pillMarginBottom + globeDiameter + tipHeight + (2 * density)).toInt()

        val bitmap = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val cx = totalWidth / 2f
        val cy = pillHeight + pillMarginBottom + radius

        val statusColor = when {
            isSos -> 0xFFFB7185.toInt()              // DarkError Red
            isSelf -> 0xFF67E8F9.toInt()             // DarkSecondary Cyan-light
            status == "paused" -> 0xFFFBBF24.toInt() // DarkWarning Amber
            else -> 0xFF22D3EE.toInt()               // DarkPrimary Electric Cyan
        }

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0D1524.toInt() // DarkSurface
            style = Paint.Style.FILL
        }

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = statusColor
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        // ==========================================
        // A. DRAW NAME PILL (ENCIMA DEL GLOBO)
        // ==========================================
        val pillRect = RectF(
            cx - pillWidth / 2f,
            0f,
            cx + pillWidth / 2f,
            pillHeight
        )
        val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xF2060913.toInt() // Deep OLED dark with slight transparency
            style = Paint.Style.FILL
        }
        val pillStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isSos) 0xFFFB7185.toInt() else 0xFF334155.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
        }
        canvas.drawRoundRect(pillRect, 6 * density, 6 * density, pillBgPaint)
        canvas.drawRoundRect(pillRect, 6 * density, 6 * density, pillStrokePaint)

        val pillTextY = pillRect.centerY() - ((namePaint.descent() + namePaint.ascent()) / 2)
        canvas.drawText(firstName, cx, pillTextY, namePaint)

        // ==========================================
        // B. DRAW PIQUITO / TIP (DEBAJO DEL GLOBO)
        // ==========================================
        val tipBottomY = cy + radius + tipHeight
        val tipPath = Path().apply {
            moveTo(cx - tipHalfWidth, cy + radius - (1 * density))
            lineTo(cx, tipBottomY)
            lineTo(cx + tipHalfWidth, cy + radius - (1 * density))
            close()
        }
        canvas.drawPath(tipPath, bgPaint)

        val tipBorderPath = Path().apply {
            moveTo(cx - tipHalfWidth, cy + radius - (0.5f * density))
            lineTo(cx, tipBottomY)
            lineTo(cx + tipHalfWidth, cy + radius - (0.5f * density))
        }
        canvas.drawPath(tipBorderPath, borderPaint)

        // ==========================================
        // C. DRAW AVATAR GLOBO (SIN DISTORSIÓN / CENTER CROP)
        // ==========================================
        canvas.drawCircle(cx, cy, radius, bgPaint)

        if (avatarBitmap != null && avatarBitmap.width > 0 && avatarBitmap.height > 0) {
            // Perfect Center-Crop (Aspect Fill) preserving 1:1 original ratio
            val shader = BitmapShader(avatarBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val matrix = Matrix()

            val scale = maxOf(
                (radius * 2f) / avatarBitmap.width.toFloat(),
                (radius * 2f) / avatarBitmap.height.toFloat()
            )
            val scaledW = avatarBitmap.width * scale
            val scaledH = avatarBitmap.height * scale
            val dx = (cx - radius) - (scaledW - radius * 2f) / 2f
            val dy = (cy - radius) - (scaledH - radius * 2f) / 2f

            matrix.setScale(scale, scale)
            matrix.postTranslate(dx, dy)
            shader.setLocalMatrix(matrix)

            val imgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                isFilterBitmap = true
                isDither = true
                this.shader = shader
            }
            canvas.drawCircle(cx, cy, radius - (1.5f * density), imgPaint)
        } else {
            // Crisp Initial Letter with cyber style
            val initial = displayName.take(1).uppercase()
            val initialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (isSos) 0xFFFB7185.toInt() else 0xFF22D3EE.toInt()
                textSize = 19 * density
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            val textY = cy - ((initialPaint.descent() + initialPaint.ascent()) / 2)
            canvas.drawText(initial, cx, textY, initialPaint)
        }

        // Draw Outer Circular Border
        canvas.drawCircle(cx, cy, radius, borderPaint)

        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }
}
