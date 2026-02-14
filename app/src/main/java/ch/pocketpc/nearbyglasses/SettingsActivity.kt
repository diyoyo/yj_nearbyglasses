package ch.pocketpc.nearbyglasses

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import androidx.core.text.HtmlCompat
import androidx.preference.EditTextPreference
import android.widget.EditText

class SettingsActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            val cooldownPref = findPreference<EditTextPreference>("cooldown_ms")
            val rssiPref = findPreference<EditTextPreference>("rssi_threshold")
            val debugIdsPref = findPreference<EditTextPreference>("debug_company_ids")
            val debugMaxLinesPref = findPreference<EditTextPreference>("debug_max_lines")

            fun refreshSummaries() {
                cooldownPref?.summary = getString(
                    R.string.summaryCooldown,
                    cooldownPref?.text ?: "10000"
                )
                rssiPref?.summary = getString(
                    R.string.summaryThreshold,
                    rssiPref?.text ?: "-75"
                )
                debugMaxLinesPref?.summary = getString(
                    R.string.summaryDebugSize,
                    debugMaxLinesPref?.text ?: "200"
                )

                val ids = debugIdsPref?.text?.trim().orEmpty()
                debugIdsPref?.summary = getString(
                    R.string.summaryDebugCompanyIds,
                    if (ids.isBlank()) "(none)" else ids
                )
            }

            // numeric input
            cooldownPref?.setOnBindEditTextListener { editText ->
                editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                editText.setSingleLine(true)
                editText.post {editText.setSelection(editText.text.length)}
            }
            rssiPref?.setOnBindEditTextListener { editText ->
                editText.inputType =
                    android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                editText.setSingleLine(true)
                editText.post {editText.setSelection(editText.text.length)}
            }
            debugMaxLinesPref?.setOnBindEditTextListener { editText ->
                editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                editText.setSingleLine(true)
                editText.post {editText.setSelection(editText.text.length)}
            }
            debugIdsPref?.setOnBindEditTextListener { editText ->
                editText.hint = "0x01AB,0x01AC,..."
                editText.setSingleLine(true)
                editText.post {editText.setSelection(editText.text.length)}
            }

            // validation + live summary update
            cooldownPref?.setOnPreferenceChangeListener { pref, newValue ->
                val s = (newValue as? String)?.trim().orEmpty()
                val v = s.toLongOrNull()
                val ok = v != null && v in 0..600_000L
                if (ok) {
                    pref.summary = getString(R.string.summaryCooldown, s)
                }
                ok
            }

            rssiPref?.setOnPreferenceChangeListener { pref, newValue ->
                val s = (newValue as? String)?.trim().orEmpty()
                val v = s.toIntOrNull()
                val ok = v != null && v in -120..0
                if (ok) {
                    pref.summary = getString(R.string.summaryThreshold, s)
                }
                ok
            }

            debugMaxLinesPref?.setOnPreferenceChangeListener { pref, newValue ->
                val s = (newValue as? String)?.trim().orEmpty()
                val v = s.toIntOrNull()
                val ok = v != null && v in 50..5000
                if (ok) {
                    pref.summary = getString(R.string.summaryDebugSize, s)
                }
                ok
            }

            debugIdsPref?.setOnPreferenceChangeListener { pref, newValue ->
                val s = (newValue as? String)?.trim().orEmpty()
                pref.summary = getString(
                    R.string.summaryDebugCompanyIds,
                    if (s.isBlank()) "(none)" else s
                )
                true
            }

            // set initial summaries
            refreshSummaries()
        }
    }
}
