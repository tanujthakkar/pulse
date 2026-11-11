package app.pulse

import androidx.lifecycle.ViewModel
import app.pulse.data.StoredBodyProfile
import app.pulse.metrics.BodyProfile
import app.pulse.metrics.Sex
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val store: StoredBodyProfile,
) : ViewModel() {
    private val reads = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pulse-profile").apply { isDaemon = true }
    }
    private val _profile = MutableStateFlow<BodyProfile?>(null)
    val profile: StateFlow<BodyProfile?> = _profile.asStateFlow()
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    init {
        reads.execute { _profile.value = store.current() }
    }

    fun save(sex: Sex, ageText: String, heightText: String, weightText: String) {
        val age = ageText.toIntOrNull()
        val height = heightText.toIntOrNull()
        val weight = weightText.toDoubleOrNull()
        if (age == null || height == null || weight == null) {
            _status.value = "Enter age, height in cm, and weight in kg."
            return
        }
        val next = BodyProfile(sex, age, height, weight)
        if (!next.valid()) {
            _status.value = "Use age 15–100, height 120–230 cm, and weight 30–250 kg."
            return
        }
        reads.execute {
            store.save(next)
            _profile.value = next
            _status.value = "Saved."
        }
    }

    override fun onCleared() {
        reads.shutdown()
    }
}
