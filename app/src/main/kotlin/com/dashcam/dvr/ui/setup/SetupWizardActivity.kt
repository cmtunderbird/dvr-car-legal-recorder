package com.dashcam.dvr.ui.setup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dashcam.dvr.R
import com.dashcam.dvr.ui.MainActivity

/**
 * SetupWizardActivity — first-run multi-step onboarding.
 *
 * Blueprint §14: "Doze mode and battery optimisation exemption must be requested
 * from the user at setup; guide the user through the exemption flow in the
 * onboarding wizard."
 *
 * Steps:
 *   0 — Welcome + jurisdiction disclaimer
 *   1 — Runtime permissions (Camera, Mic, Location, Notifications)
 *   2 — Battery optimisation exemption
 *   3 — Done → launch MainActivity
 *
 * Completion is stored in SharedPreferences "dvr_prefs" key "setup_v1_complete".
 */
class SetupWizardActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME    = "dvr_prefs"
        const val KEY_SETUP_DONE = "setup_v1_complete"
    }

    private lateinit var tvStepTitle  : TextView
    private lateinit var tvStepBody   : TextView
    private lateinit var tvWarning    : TextView
    private lateinit var btnPrimary   : Button
    private lateinit var btnSkip      : Button
    private lateinit var progressBar  : ProgressBar
    private lateinit var ivStepIcon   : ImageView

    private var currentStep = 0
    private val totalSteps  = 4

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        tvWarning.visibility = if (allGranted) View.GONE else View.VISIBLE
        tvWarning.text = "Some permissions were denied. DVR features may be limited."
        advanceStep()
    }

    private val batteryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { advanceStep() }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup_wizard)
        bindViews()
        showStep(0)
    }

    private fun bindViews() {
        tvStepTitle = findViewById(R.id.tvWizardTitle)
        tvStepBody  = findViewById(R.id.tvWizardBody)
        tvWarning   = findViewById(R.id.tvWizardWarning)
        btnPrimary  = findViewById(R.id.btnWizardPrimary)
        btnSkip     = findViewById(R.id.btnWizardSkip)
        progressBar = findViewById(R.id.wizardProgress)
        ivStepIcon  = findViewById(R.id.ivWizardIcon)
        progressBar.max = totalSteps - 1
        tvWarning.visibility = View.GONE
    }

    // ── Step renderer ───────────────────────────────────────────────────────

    private fun showStep(step: Int) {
        currentStep = step
        progressBar.progress = step
        tvWarning.visibility = View.GONE
        btnSkip.visibility = View.GONE
        when (step) {
            0 -> showWelcome()
            1 -> showPermissions()
            2 -> showBatteryExemption()
            3 -> showDone()
        }
    }

    private fun showWelcome() {
        ivStepIcon.setImageResource(android.R.drawable.ic_menu_camera)
        tvStepTitle.text = "Welcome to DVR Evidence"
        tvStepBody.text  = getString(R.string.wizard_welcome_body)
        btnPrimary.text  = "Get Started"
        btnPrimary.setOnClickListener { advanceStep() }
    }

    private fun showPermissions() {
        ivStepIcon.setImageResource(android.R.drawable.ic_menu_manage)
        tvStepTitle.text = "Required Permissions"
        tvStepBody.text  = getString(R.string.wizard_permissions_body)
        btnPrimary.text  = "Grant Permissions"
        btnSkip.visibility = View.VISIBLE
        btnSkip.setOnClickListener { advanceStep() }
        btnPrimary.setOnClickListener { requestAllPermissions() }
    }

    private fun showBatteryExemption() {
        ivStepIcon.setImageResource(android.R.drawable.ic_menu_info_details)
        tvStepTitle.text = "Battery Optimisation"
        tvStepBody.text  = getString(R.string.wizard_battery_body)
        btnPrimary.text  = "Open Battery Settings"
        btnSkip.visibility = View.VISIBLE
        btnSkip.setOnClickListener { advanceStep() }
        btnPrimary.setOnClickListener { openBatteryExemption() }
        // FIX: use post() to defer advanceStep() — avoids re-entrant showStep() call
        // which causes window-focus flicker / minimize on HyperOS when
        // isIgnoringBatteryOptimizations() returns true immediately (common on Xiaomi).
        if (isBatteryExempted()) btnPrimary.post { advanceStep() }
    }

    private fun showDone() {
        ivStepIcon.setImageResource(android.R.drawable.ic_menu_send)
        tvStepTitle.text = "You're Ready!"
        tvStepBody.text  = getString(R.string.wizard_done_body)
        btnPrimary.text  = "Start DVR"
        btnSkip.visibility = View.GONE
        btnPrimary.setOnClickListener { finishSetup() }
    }

    // ── Actions ─────────────────────────────────────────────────────────────

    private fun advanceStep() {
        val next = currentStep + 1
        if (next < totalSteps) showStep(next) else finishSetup()
    }

    private fun requestAllPermissions() {
        val required = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) advanceStep()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun openBatteryExemption() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            batteryLauncher.launch(intent)
        } catch (_: Exception) {
            // Fallback: general battery settings (works on all MIUI/HyperOS builds)
            batteryLauncher.launch(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
        }
    }

    private fun isBatteryExempted(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    /**
     * FIX: use commit() (synchronous) instead of apply() (async).
     * On HyperOS, a new MainActivity instance started immediately after apply()
     * can read a stale SharedPreferences value (false) and re-trigger the wizard,
     * causing the continuous minimize loop.
     *
     * FIX: FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK ensures the entire
     * back stack is cleared — no zombie MainActivity instances behind the new one.
     */
    private fun finishSetup() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SETUP_DONE, true)
            .commit()   // ← synchronous: prefs are written before startActivity() fires

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        finish()
    }
}
