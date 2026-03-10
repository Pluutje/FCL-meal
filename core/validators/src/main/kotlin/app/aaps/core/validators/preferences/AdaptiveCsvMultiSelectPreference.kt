package app.aaps.core.validators.preferences

import android.app.AlertDialog
import android.content.Context
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.preference.Preference
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.StringKey
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class AdaptiveCsvMultiSelectPreference(
    ctx: Context,
    private val preferences: Preferences,
    private val stringKey: StringKey,
    @StringRes titleRes: Int,
    @StringRes summaryRes: Int,
    @ArrayRes entriesRes: Int,
    @ArrayRes entryValuesRes: Int
) : Preference(ctx) {

    private val entries = ctx.resources.getStringArray(entriesRes)
    private val values = ctx.resources.getStringArray(entryValuesRes)
    private val baseSummary = ctx.getString(summaryRes)

    init {
        key = stringKey.key
        title = ctx.getString(titleRes)
        updateSummary()

        setOnPreferenceClickListener {
            showDialog()
            true
        }
    }

    private fun updateSummary() {
        val csv = preferences.get(stringKey)
        val display = if (csv.isBlank()) "-" else csv
        summary = "$baseSummary\nGekozen: $display"
    }

    private fun showDialog() {
        val current = preferences.get(stringKey)
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        val checked = values.map { current.contains(it) }.toBooleanArray()

        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMultiChoiceItems(entries, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val selected = values
                    .filterIndexed { i, _ -> checked[i] }
                    .joinToString(",")

                preferences.put(stringKey, selected)
                updateSummary()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()

    }
}
