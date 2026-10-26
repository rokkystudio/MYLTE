package badfox.logic.mylte

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import badfox.logic.neoui.NeoScreenItem
import badfox.logic.neoui.NeoScreenNavigatorView
import badfox.logic.neoui.NeoTopBarView
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView

/**
 * Shows network controls, cellular collector state, and the interactive tower map.
 */
class MainActivity : AppCompatActivity() {
    private enum class Screen {
        NETWORK_MODE,
        TOWER_MAP,
    }

    private lateinit var uiSettings: UiSettings
    private lateinit var cellRepository: CellRepository
    private lateinit var neoTopBar: NeoTopBarView
    private lateinit var bottomNavigator: NeoScreenNavigatorView
    private lateinit var networkModeScreen: View
    private lateinit var towerMapScreen: View
    private lateinit var statusText: TextView
    private lateinit var networkSummaryText: TextView
    private lateinit var collectorStatusText: TextView
    private lateinit var collectorToggleButton: Button
    private lateinit var mapStatusText: TextView
    private lateinit var registeredCellsContainer: LinearLayout
    private lateinit var neighborCellsContainer: LinearLayout
    private lateinit var towerMapView: MapView

    private var selectedScreen = Screen.NETWORK_MODE
    private var permissionDialog: AlertDialog? = null
    private var pendingCollectorStart = false
    private var collectorReceiverRegistered = false

    private val collectorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            renderCollectorState()
            if (intent?.action == CellCollectorService.ACTION_SNAPSHOT_COLLECTED) {
                renderSnapshot()
            }
        }
    }

    private val permissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        renderSnapshot()
        if (pendingCollectorStart && cellRepository.hasRequiredPermissions()) {
            requestNotificationPermissionForCollector()
        } else {
            pendingCollectorStart = false
        }
        if (selectedScreen == Screen.TOWER_MAP && !cellRepository.hasRequiredPermissions()) {
            showCellPermissionDialog()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (pendingCollectorStart && granted) {
            pendingCollectorStart = false
            startCollector()
        } else {
            pendingCollectorStart = false
            if (!granted) {
                showNotificationPermissionDialog()
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val uiSettings = UiSettings(newBase)
        val configuration = Configuration(newBase.resources.configuration)
        uiSettings.applyToConfiguration(configuration)
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
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
        towerMapView.onCreate(savedInstanceState)
        configureMap()
        renderScreen()
        renderCollectorState()
        renderSnapshot()
        startCollectorOnLaunch()
        ensureCellPermissions()
    }

    override fun onStart() {
        super.onStart()
        towerMapView.onStart()
        registerCollectorReceiver()
    }

    override fun onResume() {
        super.onResume()
        towerMapView.onResume()
        renderCollectorState()
        renderSnapshot()
        ensureCellPermissions()
    }

    override fun onPause() {
        towerMapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        unregisterCollectorReceiver()
        towerMapView.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        towerMapView.onLowMemory()
    }

    override fun onDestroy() {
        permissionDialog?.dismiss()
        permissionDialog = null
        towerMapView.onDestroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_SELECTED_SCREEN, selectedScreen.name)
        towerMapView.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    private fun bindViews() {
        neoTopBar = findViewById(R.id.neoTopBar)
        bottomNavigator = findViewById(R.id.bottomNavigator)
        networkModeScreen = findViewById(R.id.networkModeScreen)
        towerMapScreen = findViewById(R.id.towerMapScreen)
        statusText = findViewById(R.id.statusText)
        networkSummaryText = findViewById(R.id.networkSummaryText)
        collectorStatusText = findViewById(R.id.collectorStatusText)
        collectorToggleButton = findViewById(R.id.collectorToggleButton)
        mapStatusText = findViewById(R.id.mapStatusText)
        registeredCellsContainer = findViewById(R.id.registeredCellsContainer)
        neighborCellsContainer = findViewById(R.id.neighborCellsContainer)
        towerMapView = findViewById(R.id.towerMapView)
    }

    private fun bindActions() {
        collectorToggleButton.setOnClickListener {
            if (CellCollectorService.isEnabled(this)) {
                CellCollectorService.stop(this)
                renderCollectorState()
            } else {
                requestCollectorStart()
            }
        }
        findViewById<Button>(R.id.operatorSettingsButton).setOnClickListener { openSettings(Settings.ACTION_NETWORK_OPERATOR_SETTINGS) }
        findViewById<Button>(R.id.networkSettingsButton).setOnClickListener { openSettings(Settings.ACTION_WIRELESS_SETTINGS) }
        findViewById<Button>(R.id.radioInfoButton).setOnClickListener { openRadioInfo() }
    }

    private fun renderTopBar() {
        neoTopBar.setAppInfo(
            title = getString(R.string.app_name),
            subtitle = getString(R.string.android_client),
            version = BuildConfig.VERSION_NAME,
            iconResId = R.mipmap.mylte_launcher,
        )
        uiSettings.bindTopBarUi(neoTopBar) {
            uiSettings.applyAppCompatNightMode()
            recreate()
        }
    }

    private fun renderBottomNavigator() {
        bottomNavigator.setItems(
            items = listOf(
                NeoScreenItem(
                    value = Screen.NETWORK_MODE,
                    title = getString(R.string.nav_network_mode),
                    iconResId = R.drawable.ic_nav_network,
                ),
                NeoScreenItem(
                    value = Screen.TOWER_MAP,
                    title = getString(R.string.nav_tower_map),
                    iconResId = R.drawable.ic_nav_tower_map,
                ),
            ),
            selectedValue = selectedScreen,
            placement = NeoScreenNavigatorView.Placement.BOTTOM,
        ) { screen ->
            selectedScreen = screen
            renderScreen()
            renderSnapshot()
            ensureCellPermissions()
        }
    }

    private fun renderScreen() {
        networkModeScreen.visibility = if (selectedScreen == Screen.NETWORK_MODE) View.VISIBLE else View.GONE
        towerMapScreen.visibility = if (selectedScreen == Screen.TOWER_MAP) View.VISIBLE else View.GONE
        bottomNavigator.select(selectedScreen)
    }

    /**
     * Initializes the interactive MapLibre map with the Maptoolkit Summer style.
     */
    private fun configureMap() {
        towerMapView.getMapAsync { map ->
            map.setStyle(MAP_STYLE_URL)
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(20.0, 0.0))
                .zoom(2.0)
                .build()
        }
    }

    private fun registerCollectorReceiver() {
        if (collectorReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(CellCollectorService.ACTION_SNAPSHOT_COLLECTED)
            addAction(CellCollectorService.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(
            this,
            collectorReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        collectorReceiverRegistered = true
    }

    private fun unregisterCollectorReceiver() {
        if (!collectorReceiverRegistered) return
        unregisterReceiver(collectorReceiver)
        collectorReceiverRegistered = false
    }

    /**
     * Starts cellular collection when the application is launched.
     *
     * If telephony permissions are not available yet, collection starts immediately after
     * the permission request is completed successfully.
     */
    private fun startCollectorOnLaunch() {
        requestCollectorStart()
    }

    private fun requestCollectorStart() {
        pendingCollectorStart = true
        if (!cellRepository.hasRequiredPermissions()) {
            requestCellPermissions()
            return
        }
        requestNotificationPermissionForCollector()
    }

    private fun requestNotificationPermissionForCollector() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingCollectorStart = false
            startCollector()
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun showNotificationPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.collector_notification_permission_title)
            .setMessage(R.string.collector_notification_permission_text)
            .setPositiveButton(R.string.collector_notification_permission_settings) { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startCollector() {
        CellCollectorService.start(this)
        renderCollectorState()
    }

    private fun renderCollectorState() {
        val enabled = CellCollectorService.isEnabled(this)
        collectorStatusText.setText(
            if (enabled) R.string.collector_status_running else R.string.collector_status_stopped
        )
        collectorToggleButton.setText(
            if (enabled) R.string.collector_action_stop else R.string.collector_action_start
        )
    }

    private fun ensureCellPermissions() {
        if (selectedScreen == Screen.TOWER_MAP && !cellRepository.hasRequiredPermissions()) {
            showCellPermissionDialog()
        }
    }

    private fun showCellPermissionDialog() {
        if (permissionDialog?.isShowing == true) return
        permissionDialog = AlertDialog.Builder(this)
            .setTitle(R.string.main_permissions_title)
            .setMessage(R.string.main_permissions_text)
            .setPositiveButton(R.string.main_action_grant_permissions) { _, _ -> requestCellPermissions() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun requestCellPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
        )
        permissionsLauncher.launch(permissions.toTypedArray())
    }

    private fun renderSnapshot() {
        val snapshot = cellRepository.readSnapshot()
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
    }

    private fun openRadioInfo() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startExplicitActivity("com.android.phone", "com.android.phone.settings.RadioInfo")
        } else {
            startExplicitActivity("com.android.settings", "com.android.settings.RadioInfo")
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
        const val MAP_STYLE_URL = "https://styles.maptoolkit.org/summer.json"
    }
}
