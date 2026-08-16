package one.globalconnect.xtmsagent

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.globalconnect.xtmsagent.store.GlobalConnectStoreClient
import one.globalconnect.xtmsagent.store.StoreAppInstaller
import one.globalconnect.xtmsagent.store.StoreApplication
import one.globalconnect.xtmsagent.store.StoreCatalog
import java.net.HttpURLConnection
import java.net.URL

class GlobalConnectStoreActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private lateinit var loading: ProgressBar
    private lateinit var scopeLabel: TextView
    private lateinit var errorLabel: TextView
    private lateinit var retryButton: Button
    private var installingVersionId: String? = null
    private var catalog: StoreCatalog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildScreen())
        loadStore()
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(resolveThemeColor(android.R.attr.colorBackground))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(Button(this).apply {
            text = getString(R.string.store_back)
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = getString(R.string.store_title)
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        scopeLabel = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(scopeLabel)
        errorLabel = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@GlobalConnectStoreActivity, android.R.color.holo_red_dark))
            visibility = View.GONE
        }
        root.addView(errorLabel)
        retryButton = Button(this).apply {
            text = getString(R.string.store_retry)
            visibility = View.GONE
            setOnClickListener { loadStore() }
        }
        root.addView(retryButton)
        loading = ProgressBar(this).apply { isIndeterminate = true }
        root.addView(loading, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        return root
    }

    private fun loadStore() {
        loading.visibility = View.VISIBLE
        showError(null)
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { GlobalConnectStoreClient(this@GlobalConnectStoreActivity).loadCatalog() } }
                .onSuccess {
                    catalog = it
                    renderCatalog(it)
                }
                .onFailure { showError(it.message ?: getString(R.string.store_error_generic)) }
            loading.visibility = View.GONE
        }
    }

    private fun renderCatalog(store: StoreCatalog) {
        scopeLabel.text = getString(if (store.scope.equals("Group", true)) R.string.store_group_scope else R.string.store_bank_scope)
        content.removeAllViews()
        if (store.applications.isEmpty()) {
            content.addView(TextView(this).apply {
                text = getString(R.string.store_empty)
                textSize = 17f
                gravity = Gravity.CENTER
                setPadding(0, dp(48), 0, 0)
            })
            return
        }
        store.applications.forEach { content.addView(applicationCard(it)) }
    }

    private fun applicationCard(application: StoreApplication): View {
        val card = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            cardElevation = dp(2).toFloat()
            useCompatPadding = true
        }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(14) })
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        details.addView(TextView(this).apply {
            text = application.name
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        details.addView(TextView(this).apply {
            text = application.packageName
            textSize = 12f
            maxLines = 1
        })
        details.addView(TextView(this).apply {
            text = getString(R.string.store_version, application.versionName)
            textSize = 13f
        })
        row.addView(details, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val installedVersion = StoreAppInstaller.installedVersion(this, application.packageName)
        val action = Button(this).apply {
            text = getString(when {
                installingVersionId == application.versionId -> R.string.store_installing
                installedVersion == null -> R.string.store_install
                installedVersion < application.versionCode -> R.string.store_update
                else -> R.string.store_open
            })
            isEnabled = installingVersionId == null
            setOnClickListener { handleApplicationAction(application, installedVersion) }
        }
        row.addView(action)
        wrapper.addView(row)
        if (application.releaseNotes.isNotBlank()) {
            wrapper.addView(TextView(this).apply {
                text = application.releaseNotes
                maxLines = 3
                setPadding(0, dp(10), 0, 0)
            })
        }
        if (installingVersionId == application.versionId) {
            wrapper.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = false
                max = 100
                progress = 0
                tag = application.versionId
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(24)).apply { topMargin = dp(8) })
        }
        card.addView(wrapper)
        application.iconUrl?.let { loadIcon(it, icon) }
        return card
    }

    private fun handleApplicationAction(application: StoreApplication, installedVersion: Long?) {
        if (installedVersion != null && installedVersion >= application.versionCode) {
            packageManager.getLaunchIntentForPackage(application.packageName)?.let(::startActivity)
            return
        }
        if (installingVersionId != null) return
        installingVersionId = application.versionId
        catalog?.let(::renderCatalog)
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val file = GlobalConnectStoreClient(this@GlobalConnectStoreActivity).download(application) { progress ->
                        runOnUiThread {
                            content.findViewWithTag<ProgressBar>(application.versionId)?.progress = progress
                        }
                    }
                    StoreAppInstaller.install(this@GlobalConnectStoreActivity, application, file)
                }
            }.onFailure { showError(it.message ?: getString(R.string.store_install_failed)) }
            installingVersionId = null
            loadStore()
        }
    }

    private fun loadIcon(url: String, imageView: ImageView) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    try {
                        connection.connectTimeout = 10_000
                        connection.readTimeout = 15_000
                        if (connection.responseCode == HttpURLConnection.HTTP_OK) BitmapFactory.decodeStream(connection.inputStream) else null
                    } finally {
                        connection.disconnect()
                    }
                }.getOrNull()
            }
            if (bitmap != null) imageView.setImageBitmap(bitmap)
        }
    }

    private fun showError(message: String?) {
        errorLabel.text = message.orEmpty()
        errorLabel.visibility = if (message == null) View.GONE else View.VISIBLE
        retryButton.visibility = if (message == null) View.GONE else View.VISIBLE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun resolveThemeColor(attribute: Int): Int = TypedValue().let { value ->
        theme.resolveAttribute(attribute, value, true)
        value.data
    }
}
