package dev.kondraki.archon.stream

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.core.content.res.ResourcesCompat
import dev.kondraki.archon.R

/**
 * The phosphor art card (canvas ArtCard board): the player's artwork when the rig plays
 * nothing. 512 px, emblem left and words right, everything inside the middle 60 % because the
 * Pixel lock screen crops square art to ~3:2. Red while healthy, amber while connecting or lost;
 * the Pixel tints its whole player from it. Redrawn on state changes only, never on a timer.
 */
object ArtCard {
    fun render(ctx: Context, word: String, detail: String, warm: Boolean, crt: Boolean,
               primary: Int, secondary: Int, on: Int, onVar: Int): Bitmap {
        val s = 512f
        val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        val accent = if (warm) secondary else primary
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        if (crt) {                                          // scanlines, tinted like the deck's
            p.color = Color.argb(23, Color.red(primary), Color.green(primary), Color.blue(primary))
            var y = 2f; while (y < s) { c.drawRect(0f, y, s, y + 1, p); y += 3f }
        }
        // frame + the two corner brackets
        val inset = 26f
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f
        p.color = Color.argb(140, Color.red(accent), Color.green(accent), Color.blue(accent))
        c.drawRect(inset, inset, s - inset, s - inset, p)
        p.strokeWidth = 4f; p.color = accent
        c.drawLine(inset, inset, inset + 21, inset, p); c.drawLine(inset, inset, inset, inset + 21, p)
        c.drawLine(s - inset, s - inset, s - inset - 21, s - inset, p); c.drawLine(s - inset, s - inset, s - inset, s - inset - 21, p)
        p.style = Paint.Style.FILL

        // the emblem, glowing (a soft halo under it, then the mark)
        val ex = 52f; val ey = 179f; val es = 154f
        p.shader = RadialGradient(ex + es / 2, ey + es / 2, es * 0.75f,
            Color.argb(90, Color.red(accent), Color.green(accent), Color.blue(accent)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawCircle(ex + es / 2, ey + es / 2, es * 0.75f, p); p.shader = null
        ResourcesCompat.getDrawable(ctx.resources, R.drawable.emblem, null)?.mutate()?.apply {
            setTint(accent); setBounds(ex.toInt(), ey.toInt(), (ex + es).toInt(), (ey + es).toInt()); draw(c)
        }

        // words, all inside the safe band (y 102..410)
        val chakra = ResourcesCompat.getFont(ctx, R.font.chakra_petch_bold)
        val plex = ResourcesCompat.getFont(ctx, R.font.plex_mono_semibold)
        val plexReg = ResourcesCompat.getFont(ctx, R.font.plex_mono_regular)
        val x = 235f
        p.typeface = chakra; p.textSize = 34f; p.letterSpacing = 0.2f; p.color = on
        c.drawText("LIGHTNING", x, 222f, p)
        p.typeface = plex; p.textSize = 25f; p.letterSpacing = 0f; p.color = accent
        p.setShadowLayer(12f, 0f, 0f, accent)
        c.drawText("[ $word ]", x, 270f, p)
        p.clearShadowLayer()
        p.typeface = plexReg; p.textSize = 17f; p.color = onVar
        detail.lines().take(2).forEachIndexed { i, line -> c.drawText(line, x, 310f + i * 26f, p) }

        if (crt) {                                          // vignette
            p.shader = RadialGradient(s / 2, s / 2, s * 0.72f, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(170, 0, 0, 0)),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, s, s, p); p.shader = null
        }
        return bmp
    }
}
