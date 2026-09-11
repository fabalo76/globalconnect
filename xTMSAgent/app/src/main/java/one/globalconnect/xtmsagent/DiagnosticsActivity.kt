package one.globalconnect.xtmsagent

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.globalconnect.xtmsagent.diagnostics.NexgoDiagnosticsManager

class DiagnosticsActivity : AppCompatActivity() {
    private lateinit var reportView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.diagnostics_title)
        }
        setContentView(buildContent())
        refreshReport()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun buildContent(): ScrollView {
        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        reportView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@DiagnosticsActivity).apply {
                text = getString(R.string.diagnostics_refresh)
                setOnClickListener { refreshReport() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@DiagnosticsActivity).apply {
                text = getString(R.string.diagnostics_share)
                setOnClickListener { shareReport() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(buttons)
            addView(Button(this@DiagnosticsActivity).apply {
                text = getString(R.string.diagnostics_export)
                setOnClickListener { exportReport() }
            })
            addView(reportView)
        }
        return ScrollView(this).apply { addView(content) }
    }

    private fun refreshReport() {
        reportView.text = getString(R.string.diagnostics_loading)
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                NexgoDiagnosticsManager.refresh(this@DiagnosticsActivity)
                NexgoDiagnosticsManager.readDisplayReport(this@DiagnosticsActivity)
            }
            reportView.text = report
        }
    }

    private fun shareReport() {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                NexgoDiagnosticsManager.shareFile(this@DiagnosticsActivity)
            }
            val uri = FileProvider.getUriForFile(
                this@DiagnosticsActivity,
                "$packageName.provider",
                file,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, getString(R.string.diagnostics_share_title))
            if (chooser.resolveActivity(packageManager) == null) {
                Toast.makeText(
                    this@DiagnosticsActivity,
                    R.string.diagnostics_no_share_target,
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                startActivity(chooser)
            }
        }
    }

    private fun exportReport() {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    NexgoDiagnosticsManager.exportToPublicDownloads(this@DiagnosticsActivity)
                }
            }.onSuccess { export ->
                Toast.makeText(
                    this@DiagnosticsActivity,
                    getString(R.string.diagnostics_exported, export.displayPath),
                    Toast.LENGTH_LONG,
                ).show()
                reportView.text = withContext(Dispatchers.IO) {
                    NexgoDiagnosticsManager.readDisplayReport(this@DiagnosticsActivity)
                }
            }.onFailure { exception ->
                Toast.makeText(
                    this@DiagnosticsActivity,
                    getString(
                        R.string.diagnostics_export_failed,
                        exception.message ?: exception.javaClass.simpleName,
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}
