package app.aaps.core.validators.preferences

import android.app.TimePickerDialog
import android.content.Context
import android.util.AttributeSet
import androidx.annotation.StringRes
import androidx.preference.Preference
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.StringKey

class AdaptiveTimePreference(
    ctx: Context,
    attrs: AttributeSet? = null,
    private val preferences: Preferences,
    private val stringKey: StringKey,
    @StringRes titleRes: Int,
    @StringRes summaryRes: Int
) : Preference(ctx, attrs) {

    private val baseSummary = ctx.getString(summaryRes)

    init {
        key = stringKey.key
        title = ctx.getString(titleRes)
        updateSummary()

        setOnPreferenceClickListener {
            showTimePicker()
            true
        }
    }

    private fun updateSummary() {
        val current = preferences.get(stringKey)
        summary = "$baseSummary\nHuidig: $current"
    }

    private fun showTimePicker() {
        val current = preferences.get(stringKey)
        val parts = current.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0

        TimePickerDialog(
            context,
            { _, h, m ->
                val value = String.format("%02d:%02d", h, m)
                preferences.put(stringKey, value)
                updateSummary()
            },
            hour,
            minute,
            true
        ).show()
    }
}
