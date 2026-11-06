package mx.oli.glass

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.service.dreams.DreamService
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import java.util.Calendar

/**
 * Protector de pantalla del sistema: la obra del momento a pantalla completa,
 * con su ficha y la hora. Hecho con vistas simples para gastar lo mínimo de memoria.
 */
class GalleryDream : DreamService() {

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true

        val art = ArtCatalog.pick(Calendar.getInstance())
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            ArtLoader.decodeBitmap(this@GalleryDream, art)?.let { setImageBitmap(it) }
                ?: setBackgroundColor(art.ground2.toInt())
        }
        root.addView(image, FrameLayout.LayoutParams(-1, -1))

        val veil = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00000000, 0x00000000, 0x9905080E.toInt()),
            )
        }
        root.addView(veil, FrameLayout.LayoutParams(-1, -1))

        val dp = resources.displayMetrics.widthPixels / 1920f
        val label = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = art.title
                setTextColor(Color.WHITE)
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                textSize = 34f * dp / resources.displayMetrics.scaledDensity
                setShadowLayer(20f, 0f, 3f, 0x66000000)
            })
            addView(TextView(context).apply {
                text = "${art.artist} · ${art.year} · ${art.series}"
                setTextColor(0xD9FFFFFF.toInt())
                textSize = 21f * dp / resources.displayMetrics.scaledDensity
            })
        }
        root.addView(label, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            setMargins((96 * dp).toInt(), 0, 0, (80 * dp).toInt())
        })

        val clock = TextClock(this).apply {
            format12Hour = "h:mm"
            format24Hour = "H:mm"
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            textSize = 64f * dp / resources.displayMetrics.scaledDensity
        }
        root.addView(clock, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, (96 * dp).toInt(), (72 * dp).toInt())
        })

        setContentView(root)
        image.scaleX = 1.04f
        image.scaleY = 1.04f
        image.animate().scaleX(1.12f).scaleY(1.12f).translationX(-20 * dp).setDuration(90_000).start()
    }
}
