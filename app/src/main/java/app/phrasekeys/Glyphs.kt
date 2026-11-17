package app.phrasekeys

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.widget.TextView

/** Один слой значка: контур в клетке 24×24 (команды M, L, Z) и заливка — сплошная или градиент. */
class IconLayer(val d: String, val c1: Int, val c2: Int, val tint: Boolean) {
    val path: Path by lazy { parse(d) }

    private fun parse(d: String): Path {
        val p = Path()
        p.fillType = Path.FillType.EVEN_ODD
        val tokens = d.split(' ')
        var i = 0
        while (i < tokens.size) {
            when (tokens[i]) {
                "M" -> {
                    p.moveTo(tokens[i + 1].toFloat(), tokens[i + 2].toFloat())
                    i += 3
                }
                "L" -> {
                    i++
                    while (i + 1 < tokens.size && tokens[i] != "Z" && tokens[i] != "M") {
                        p.lineTo(tokens[i].toFloat(), tokens[i + 1].toFloat())
                        i += 2
                    }
                }
                "Z" -> {
                    p.close()
                    i++
                }
                else -> i++
            }
        }
        return p
    }
}

class Icon(val layers: List<IconLayer>)

/** Рисует значок [icon] в границах drawable. Однотонные слои красятся в [tint]. */
class VectorGlyph(private val icon: Icon, private val tint: Int = Color.WHITE) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(b.width() / 24f, b.height() / 24f)
        for (layer in icon.layers) {
            paint.shader = null
            if (layer.tint) {
                val a = Color.alpha(layer.c1) * Color.alpha(tint) / 255
                paint.color = Color.argb(a, Color.red(tint), Color.green(tint), Color.blue(tint))
            } else if (layer.c1 != layer.c2) {
                paint.shader = LinearGradient(0f, 0f, 0f, 24f, layer.c1, layer.c2, Shader.TileMode.CLAMP)
            } else {
                paint.color = layer.c1
            }
            canvas.drawPath(layer.path, paint)
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Клавиша со значком по центру (стрелка, стереть и т.п.). */
open class GlyphKey(context: Context) : TextView(context) {
    var glyph: VectorGlyph? = null
    var glyphPx: Int = 0

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val g = glyph ?: return
        val size = glyphPx
        val left = (width - size) / 2
        val top = (height - size) / 2
        g.setBounds(left, top, left + size, top + size)
        g.draw(canvas)
    }
}

/** Пробел: название языка по центру и стрелки-шевроны по краям. */
class SpaceKey(context: Context) : TextView(context) {
    var tint: Int = Color.GRAY
    private var leftTinted = VectorGlyph(Icons.CHEV_LEFT, tint)
    private var rightTinted = VectorGlyph(Icons.CHEV_RIGHT, tint)
    private var builtFor = tint

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (builtFor != tint) {
            leftTinted = VectorGlyph(Icons.CHEV_LEFT, tint)
            rightTinted = VectorGlyph(Icons.CHEV_RIGHT, tint)
            builtFor = tint
        }
        val size = (height * 0.34f).toInt()
        val margin = (height * 0.28f).toInt()
        val top = (height - size) / 2
        leftTinted.setBounds(margin, top, margin + size, top + size)
        leftTinted.draw(canvas)
        rightTinted.setBounds(width - margin - size, top, width - margin, top + size)
        rightTinted.draw(canvas)
    }
}

/**
 * Выпуклая «стеклянная» клавиша в стиле iOS: мягкая тень снизу, лёгкий градиент,
 * блик на верхней половине и тонкая светлая кромка. Шейдеры и контуры создаются один раз
 * при смене размера, а не при каждой отрисовке — клавиатура не тормозит.
 */
class GlassDrawable(
    private val fill: Int,
    private val radius: Float,
    private val depth: Float,
    private val pressed: Boolean
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = RectF()
    private val shadow = RectF()
    private val rim = RectF()
    private val clip = Path()
    private var bodyShader: Shader? = null
    private var glossShader: Shader? = null
    private var rimShader: Shader? = null
    private var glossBottom = 0f
    private val shadowColor = Color.argb(if (pressed) 40 else 90, 0, 0, 0)

    private fun tone(c: Int, f: Float): Int {
        fun ch(v: Int): Int =
            if (f >= 1f) (v + (255 - v) * (f - 1f)).toInt().coerceIn(0, 255)
            else (v * f).toInt().coerceIn(0, 255)
        return Color.argb(Color.alpha(c), ch(Color.red(c)), ch(Color.green(c)), ch(Color.blue(c)))
    }

    override fun onBoundsChange(b: android.graphics.Rect) {
        if (b.isEmpty) return
        val lift = if (pressed) depth * 0.35f else depth
        body.set(b.left + 0.5f, b.top + (depth - lift) + 0.5f, b.right - 0.5f, b.bottom - lift - 0.5f)
        shadow.set(body.left, body.top + lift, body.right, body.bottom + lift)
        rim.set(body.left + 0.6f, body.top + 0.6f, body.right - 0.6f, body.bottom - 0.6f)

        val top = if (pressed) 1.0f else 1.16f
        val bottom = if (pressed) 0.84f else 0.90f
        bodyShader = LinearGradient(
            0f, body.top, 0f, body.bottom, tone(fill, top), tone(fill, bottom), Shader.TileMode.CLAMP
        )
        glossBottom = body.top + body.height() * 0.52f
        glossShader = LinearGradient(
            0f, body.top, 0f, glossBottom,
            Color.argb(if (pressed) 18 else 84, 255, 255, 255), Color.argb(0, 255, 255, 255),
            Shader.TileMode.CLAMP
        )
        rimShader = LinearGradient(
            0f, body.top, 0f, body.bottom,
            Color.argb(if (pressed) 60 else 150, 255, 255, 255), Color.argb(16, 255, 255, 255),
            Shader.TileMode.CLAMP
        )
        clip.reset()
        clip.addRoundRect(body, radius, radius, Path.Direction.CW)
    }

    override fun draw(canvas: Canvas) {
        if (bodyShader == null) return
        paint.style = Paint.Style.FILL

        paint.shader = null
        paint.color = shadowColor
        canvas.drawRoundRect(shadow, radius, radius, paint)

        paint.shader = bodyShader
        canvas.drawRoundRect(body, radius, radius, paint)

        canvas.save()
        canvas.clipPath(clip)
        paint.shader = glossShader
        canvas.drawRect(body.left, body.top, body.right, glossBottom, paint)
        canvas.restore()

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f
        paint.shader = rimShader
        canvas.drawRoundRect(rim, radius, radius, paint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
