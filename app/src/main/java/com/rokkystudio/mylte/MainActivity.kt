package com.rokkystudio.mylte

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.rokkystudio.sharedapp.neoui.NeoScreenItem
import com.rokkystudio.sharedapp.neoui.NeoScreenNavigatorView
import com.rokkystudio.sharedapp.neoui.NeoTopBarView

/**
 * Shows network mode entry points and cell tower map data exposed by Android telephony APIs.
 */
class MainActivity : AppCompatActivity() {
    private enum class Screen {
        NETWORK_MODE,
        TOWER_MAP,
    }

    private lateinit var uiSettings: UiSettings
    private lateinit var cellRepository: CellRepository
    private val mapRenderer = TowerMapRenderer()

    private lateinit var neoTopBar: NeoTopBarView
    private lateinit var bottomNavigator: NeoScreenNavigatorView
    private lateinit var networkModeScreen: View
    private lateinit var towerMapScreen: View
    private lateinit var permissionsCard: View
    private lateinit var statusText: TextView
    private lateinit var networkSummaryText: TextView
    private lateinit var mapStatusText: TextView
    private lateinit var registeredCellsContainer: LinearLayout
    private lateinit var neighborCellsContainer: LinearLayout
    private lateinit var towerMapView: WebView

    private var selectedScreen = Screen.NETWORK_MODE

    private val permissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        renderSnapshot()
    }

    override fun attachBaseContext(newBase: Context) {
        val uiSettings = UiSettings(newBase)
        val configuration = Configuration(newBase.resources.configuration)
        uiSettings.applyToConfiguration(configuration)
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        uiSettings = UiSettings(this)
        cellRepository = CellRepository(applicationContext)
        selectedScreen = savedInstanceState?.getString(KEY_SELECTED_SCREEN)
            ?.let { runCatching { Screen.valueOf(it) }.getOrNull() }
            ?: Screen.NETWORK_MODE
        bindViews()
        bindActions()
        renderTopBar()
        renderBottomNavigator()
        configureMap()
        renderScreen()
        renderSnapshot()
    }

    override fun onResume() {
        super.onResume()
        renderSnapshot()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_SELECTED_SCREEN, selectedScreen.name)
        super.onSaveInstanceState(outState)
    }

    private fun bindViews() {
        neoTopBar = findViewById(R.id.neoTopBar)
        bottomNavigator = findViewById(R.id.bottomNavigator)
        networkModeScreen = findViewById(R.id.networkModeScreen)
        towerMapScreen = findViewById(R.id.towerMapScreen)
        permissionsCard = findViewById(R.id.permissionsCard)
        statusText = findViewById(R.id.statusText)
        networkSummaryText = findViewById(R.id.networkSummaryText)
        mapStatusText = findViewById(R.id.mapStatusText)
        registeredCellsContainer = findViewById(R.id.registeredCellsContainer)
        neighborCellsContainer = findViewById(R.id.neighborCellsContainer)
        towerMapView = findViewById(R.id.towerMapView)
    }

    private fun bindActions() {
        findViewById<Button>(R.id.grantPermissionsButton).setOnClickListener { requestCellPermissions() }
        findViewById<Button>(R.id.refreshButton).setOnClickListener { renderSnapshot() }
        findViewById<Button>(R.id.operatorSettingsButton).setOnClickListener { openSettings(Settings.ACTION_NETWORK_OPERATOR_SETTINGS) }
        findViewById<Button>(R.id.networkSettingsButton).setOnClickListener { openSettings(Settings.ACTION_WIRELESS_SETTINGS) }
        findViewById<Button>(R.id.advancedTestingButton).setOnClickListener { openAdvancedTestingMenu() }
        findViewById<Button>(R.id.android11RadioButton).setOnClickListener { openAndroid11RadioInfo() }
        findViewById<Button>(R.id.legacyRadioButton).setOnClickListener { openLegacyRadioInfo() }
        findViewById<Button>(R.id.android11MinusRadioButton).setOnClickListener { openAndroid11MinusRadioInfo() }
    }

    private fun renderTopBar() {
        neoTopBar.setAppInfo(
            title = getString(R.string.app_name),
            subtitle = getString(R.string.android_client),
            version = BuildConfig.VERSION_NAME,
            iconResId = R.mipmap.ic_launcher,
        )
        uiSettings.bindTopBarUi(neoTopBar) {
            uiSettings.applyAppCompatNightMode()
            recreate()
        }
    }

    private fun renderBottomNavigator() {
        bottomNavigator.setItems(
            items = listOf(
                NeoScreenItem(Screen.NETWORK_MODE, getString(R.string.nav_network_mode)),
                NeoScreenItem(Screen.TOWER_MAP, getString(R.string.nav_tower_map)),
            ),
            selectedValue = selectedScreen,
            placement = NeoScreenNavigatorView.Placement.BOTTOM,
        ) { screen ->
            selectedScreen = screen
            renderScreen()
        }
    }

    private fun renderScreen() {
        networkModeScreen.visibility = if (selectedScreen == Screen.NETWORK_MODE) View.VISIBLE else View.GONE
        towerMapScreen.visibility = if (selectedScreen == Screen.TOWER_MAP) View.VISIBLE else View.GONE
        bottomNavigator.select(selectedScreen)
    }

    private fun configureMap() {
        towerMapView.settings.javaScriptEnabled = true
        towerMapView.settings.domStorageEnabled = true
    }

    private fun requestCellPermissions() {
        permissionsLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.READ_PHONE_STATE,
            )
        )
    }

    private fun renderSnapshot() {
        val snapshot = cellRepository.readSnapshot()
        permissionsCard.visibility = if (cellRepository.hasRequiredPermissions()) View.GONE else View.VISIBLE
        when (snapshot) {
            CellSnapshot.PermissionRequired -> renderPermissionRequired()
            CellSnapshot.Unavailable -> renderUnavailable()
            is CellSnapshot.Available -> renderAvailable(snapshot)
        }
    }

    private fun renderPermissionRequired() {
        statusText.setText(R.string.main_status_permission_required)
        networkSummaryText.text = getString(R.string.main_permissions_text)
        renderCells(emptyList(), registeredCellsContainer)
        renderCells(emptyList(), neighborCellsContainer)
        renderMap(emptyList())
    }

    private fun renderUnavailable() {
        statusText.setText(R.string.main_status_no_telephony)
        networkSummaryText.text = ""
        renderCells(emptyList(), registeredCellsContainer)
        renderCells(emptyList(), neighborCellsContainer)
        renderMap(emptyList())
    }

    private fun renderAvailable(snapshot: CellSnapshot.Available) {
        statusText.setText(R.string.main_status_ready)
        networkSummaryText.text = getString(
            R.string.main_network_summary,
            snapshot.operatorName.ifBlank { getString(R.string.common_not_available) },
            snapshot.operatorCode.ifBlank { getString(R.string.common_not_available) },
            snapshot.simOperatorName.ifBlank { getString(R.string.common_not_available) },
            snapshot.simOperatorCode.ifBlank { getString(R.string.common_not_available) },
            snapshot.networkType,
            snapshot.phoneType,
        )
        renderCells(snapshot.records.filter { it.registered }, registeredCellsContainer)
        renderCells(snapshot.records.filter { !it.registered }, neighborCellsContainer)
        renderMap(snapshot.records)
    }

    private fun renderCells(records: List<CellTowerRecord>, container: LinearLayout) {
        container.removeAllViews()
        if (records.isEmpty()) {
            container.addView(createEmptyText())
            return
        }
        records.forEach { record ->
            container.addView(createCellView(record))
        }
    }

    private fun createEmptyText(): TextView {
        return TextView(this).apply {
            text = getString(R.string.main_no_cells)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 13f
            includeFontPadding = false
        }
    }

    private fun createCellView(record: CellTowerRecord): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(12))
            addView(TextView(context).apply {
                text = record.title()
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                textSize = 15f
                includeFontPadding = false
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            addView(TextView(context).apply {
                text = record.details()
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                textSize = 12f
                includeFontPadding = false
                setPadding(0, dp(6), 0, 0)
            })
        }
    }

    private fun renderMap(records: List<CellTowerRecord>) {
        val coordinateRecords = records.filter { it.hasCoordinates() }
        mapStatusText.text = if (coordinateRecords.isEmpty()) {
            getString(R.string.main_map_no_coordinates)
        } else {
            resources.getQuantityString(R.plurals.main_map_records, coordinateRecords.size, coordinateRecords.size)
        }
        towerMapView.loadDataWithBaseURL(
            "https://tile.openstreetmap.org/",
            mapRenderer.render(coordinateRecords),
            "text/html",
            "UTF-8",
            null,
        )
    }

    private fun openAdvancedTestingMenu() {
        runActivityManagerCommand("am start --user 0 -n com.android.settings/.Settings\$TestingSettingsActivity")
    }

    private fun openAndroid11RadioInfo() {
        startExplicitActivity("com.android.phone", "com.android.phone.settings.RadioInfo")
    }

    private fun openLegacyRadioInfo() {
        runActivityManagerCommand("am start --user 0 -n com.android.settings/.RadioInfo")
    }

    private fun openAndroid11MinusRadioInfo() {
        startExplicitActivity("com.android.settings", "com.android.settings.RadioInfo")
    }

    private fun runActivityManagerCommand(command: String) {
        try {
            Runtime.getRuntime().exec(command)
            Toast.makeText(this, R.string.main_system_menu_opening, Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            Toast.makeText(this, error.localizedMessage ?: command, Toast.LENGTH_LONG).show()
        }
    }

    private fun startExplicitActivity(packageName: String, className: String) {
        try {
            startActivity(Intent(Intent.ACTION_MAIN).setClassName(packageName, className))
        } catch (error: ActivityNotFoundException) {
            Toast.makeText(this, R.string.main_system_menu_not_found, Toast.LENGTH_LONG).show()
        } catch (error: SecurityException) {
            Toast.makeText(this, error.localizedMessage ?: getString(R.string.main_system_menu_not_found), Toast.LENGTH_LONG).show()
        }
    }

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action))
        } catch (error: ActivityNotFoundException) {
            Toast.makeText(this, action, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private companion object {
        const val KEY_SELECTED_SCREEN = "selected_screen"
    }
}