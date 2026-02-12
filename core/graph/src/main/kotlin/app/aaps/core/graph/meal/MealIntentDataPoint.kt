package app.aaps.core.graph.meal

import android.content.Context
import android.graphics.Paint
import app.aaps.core.graph.data.DataPointWithLabelInterface
import app.aaps.core.graph.data.Shape

class MealIntentDataPoint(
    private val x: Double,
    private var y: Double,
    val startX: Double,
    val endX: Double,
    override val label: String,
    val style: MealIntentVisualStyle,
    val bandHeightPx: Float,
    val popupText: String = ""
) : DataPointWithLabelInterface {

    override fun getX() = x
    override fun getY() = y
    override fun setY(y: Double) { this.y = y }

    override val duration: Long
        get() = (endX - startX).toLong()

    override val shape = Shape.MEAL_INTENT
    override val size = bandHeightPx      // ✅ NOOIT 0
    override val paintStyle = Paint.Style.FILL

    override fun color(context: Context?): Int =
        style.textColor

    fun buildPopupText(): String {
        if (popupText.isNotBlank()) return popupText
        return label
    }

}
