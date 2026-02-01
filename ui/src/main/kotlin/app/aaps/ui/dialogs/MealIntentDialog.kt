package app.aaps.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.ui.R
import app.aaps.ui.databinding.DialogMealIntentBinding
import javax.inject.Inject

class MealIntentDialog : DialogFragmentWithDate() {

    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var uel: UserEntryLogger

    private var _binding: DialogMealIntentBinding? = null
    private val binding get() = _binding!!

    enum class MealSize { SMALL, NORMAL, LARGE }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        onCreateViewGeneral()
        _binding = DialogMealIntentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun submit(): Boolean {
        if (_binding == null) return false

        val selectedSize = when (binding.mealSizeGroup.checkedRadioButtonId) {
            R.id.meal_small -> MealSize.SMALL
            R.id.meal_large -> MealSize.LARGE
            else -> MealSize.NORMAL
        }

        val payload = "MEAL_INTENT:${selectedSize.name}"

        activity?.let { activity ->
            OKDialog.showConfirmation(
                activity,
                rh.gs(R.string.meal_intent_title),
                rh.gs(R.string.meal_intent_confirm, selectedSize.name),
                {
                    // MVP: UserEntryLogger is the canonical path (and already used in InsulinDialog)
                    uel.log(
                        Action.MEAL_INTENT,
                        Sources.MealIntentDialog,
                        payload
                    )
                }
            )
        }

        return true
    }
}
