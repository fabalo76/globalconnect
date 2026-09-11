package one.globalconnect.xtmsagent

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import one.globalconnect.xtmsagent.mqtt.TmsMqttManager
import one.globalconnect.xtmsagent.mqtt.persistence.TmsCredentialStore
import one.globalconnect.xtmsagent.nexgo.PhysicalKeypadInputPolicy

/**
 * Full-screen activity shown when the TMS operator blocks this terminal.
 *
 * - Non-dismissable: back button is suppressed.
 * - Launched with FLAG_ACTIVITY_NEW_TASK so it can be started from any context
 *   (e.g. from TmsNotificationHandler running in an MQTT callback thread).
 * - launchMode="singleTop" + taskAffinity="" ensures it floats in its own task;
 *   re-block commands deliver a fresh message via onNewIntent().
 * - [instance] is the static handle used by TmsNotificationHandler to finish()
 *   the activity when the remote unblock command arrives.
 * - "Unlock by Code" button is shown only when the block packet carried a 10-digit
 *   offline unlock code. Entering the correct code performs a self-unlock.
 */
class BlockedActivity : AppCompatActivity() {

    companion object {
        /** Held so TmsNotificationHandler can finish() on remote unblock. Thread-safe read. */
        @Volatile var instance: BlockedActivity? = null
    }

    private lateinit var credentialStore: TmsCredentialStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the screen on and go truly full-screen (no status/nav bar).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        supportActionBar?.hide()

        setContentView(R.layout.activity_blocked)
        credentialStore = TmsCredentialStore(this)
        instance = this

        applyState()
    }

    /** Called by the OS when singleTop re-uses the existing instance (re-block). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyState()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    @Deprecated("Deprecated in Java")
    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        // Terminal is blocked — back button intentionally does nothing.
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun applyState() {
        val msg = credentialStore.loadBlockMessage()
            ?: getString(R.string.block_default_message)
        findViewById<TextView>(R.id.tv_block_message).text = msg

        val termId = credentialStore.loadTermId()
        val tvSerial = findViewById<TextView>(R.id.tv_serial_number)
        if (termId != null) {
            tvSerial.text = "S/N: $termId"
            tvSerial.visibility = View.VISIBLE
        } else {
            tvSerial.visibility = View.GONE
        }

        val unlockCode = credentialStore.loadUnlockCode()
        val btnUnlock = findViewById<Button>(R.id.btnUnlockByCode)
        btnUnlock.visibility = if (unlockCode != null) View.VISIBLE else View.GONE
        btnUnlock.setOnClickListener { showUnlockCodeDialog() }
    }

    private fun showUnlockCodeDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.unlock_code_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            maxLines = 1
            filters = arrayOf(android.text.InputFilter.LengthFilter(10))
        }
        PhysicalKeypadInputPolicy.configure(input)

        AlertDialog.Builder(this)
            .setTitle(R.string.unlock_code_title)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val entered = input.text.toString().trim()
                val expected = credentialStore.loadUnlockCode()
                if (entered.length == 10 && entered == expected) {
                    performSelfUnlock()
                } else {
                    // Show error and re-open so the operator can retry.
                    AlertDialog.Builder(this)
                        .setMessage(R.string.unlock_code_invalid)
                        .setPositiveButton(R.string.ok) { _, _ -> showUnlockCodeDialog() }
                        .show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun performSelfUnlock() {
        // Publish blk=0 immediately so the server clears the block without waiting
        // for the next periodic status report (~20 min). If not connected right now,
        // the commitSelfUnlock() call below sets isSelfUnlockPending so blk=0 goes
        // out on the next report.
        TmsMqttManager.publishSelfUnlock()

        // Write all block-state changes in a single synchronous commit so the data
        // is guaranteed on disk before we return. Using .apply() (async) risks losing
        // the write if exitProcess() is triggered by the Activity lifecycle shortly
        // after this call (which happens when MainActivity.onDestroy fires).
        credentialStore.commitSelfUnlock()

        // Simply finish this activity. BlockedActivity lives in its own task
        // (taskAffinity="") so finishing it brings the existing MainActivity task
        // back to the foreground — no need to start a new MainActivity instance,
        // which would destroy the existing one and trigger onDestroy/exitProcess.
        finish()
    }
}
