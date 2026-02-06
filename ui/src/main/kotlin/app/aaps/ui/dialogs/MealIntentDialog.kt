package app.aaps.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.meal.MealIntentRepository
import app.aaps.core.interfaces.meal.MealIntentType
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.DoubleKey
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.ui.R
import app.aaps.ui.databinding.DialogMealIntentBinding
import javax.inject.Inject
import org.joda.time.DateTime

class MealIntentDialog : DialogFragmentWithDate() {

    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var prefs: Preferences

    private var _binding: DialogMealIntentBinding? = null
    private val binding get() = _binding!!

    // vaste TTL per type (niet zichtbaar voor gebruiker)
    private fun ttlMinutes(type: MealIntentType): Int =
        when (type) {
            MealIntentType.SMALL  -> 90
            MealIntentType.NORMAL -> 120
            MealIntentType.LARGE  -> 180
            else -> 120
        }

    private fun preBolusU(type: MealIntentType): Double =
        when (type) {
            MealIntentType.SMALL  -> prefs.get(DoubleKey.prebolus_small)
            MealIntentType.NORMAL -> prefs.get(DoubleKey.prebolus_normal)
            MealIntentType.LARGE  -> prefs.get(DoubleKey.prebolus_large)
            else -> 0.0
        }

    private fun selectedMealType(): MealIntentType =
        when (binding.mealSizeGroup.checkedRadioButtonId) {
            R.id.meal_small -> MealIntentType.SMALL
            R.id.meal_large -> MealIntentType.LARGE
            else            -> MealIntentType.NORMAL
        }

    private fun updateUI(type: MealIntentType) {

        // ✅ Enige uitlegtekst: afkomstig uit MealIntentType
        binding.mealHint.text = type.uiLabel()

        val bolus = preBolusU(type)
        binding.prebolusValue.text =
            "Pre-bolus: ${"%.2f".format(bolus)} E"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        onCreateViewGeneral()
        _binding = DialogMealIntentBinding.inflate(inflater, container, false)

        // init met default NORMAL
        updateUI(MealIntentType.NORMAL)

        binding.mealSizeGroup.setOnCheckedChangeListener { _, _ ->
            updateUI(selectedMealType())
        }

        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun submit(): Boolean {
        if (_binding == null) return false

        val type = selectedMealType()
        val bolusU = preBolusU(type)
        val ttl = ttlMinutes(type)

        val validUntil = DateTime.now().plusMinutes(ttl)
        val validUntilText = validUntil.toString("HH:mm")

        val payload =
            "MEAL_INTENT:${type.name},PREBOLUS=${"%.2f".format(bolusU)}U,TTL=${ttl}m"

        activity?.let { activity ->
            OKDialog.showConfirmation(
                activity,
                rh.gs(R.string.meal_intent_title),
                "Pre-bolus: ${"%.2f".format(bolusU)} E\nGeldig tot $validUntilText",
                {
                    MealIntentRepository.set(
                        type = type,
                        ttlMinutes = ttl
                    )
                    // NB: preBolusU wordt downstream gebruikt bij het vuren

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
