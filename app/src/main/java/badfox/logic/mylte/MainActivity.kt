package badfox.logic.mylte

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageButton
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
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * Shows network controls, cellular collector state, current precise GPS position, and cached tower estimates.
 */
class MainActivity : AppCompatActivity() {
    private enum class Screen {
        NETWORK_MODE,
        TOWER_MAP,
    }

    private lateinit var uiSettings: UiSettings
    private lateinit var cellRepository: CellRepository
    private lateinit var cellDatabase: CellDatabase
    private lateinit var neoTopBar: NeoTopBarView
    private lateinit var bottomNavigator: NeoScreenNavigatorView
    private lateinit var networkModeScreen: View
    private lateinit var towerMapScreen: View
    private lateinit var statusText: TextView
    private lateinit var networkSummaryText: TextView
    private lateinit var collectorStatusText: TextView
    private lateinit var collectorToggleButton: Button
    private lateinit var myLocationButton: ImageButton
    private lateinit var towerMapView: MapView
    private lateinit var towerDetailsPanel: View
    private lateinit var towerDetailsTitle: TextView
    private lateinit var towerDetailsText: TextView

    private var towerMap: MapLibreMap? = null
    private var userLocationMarker: Marker? = null
    private val towerMarkers = mutableListOf<Marker>()
    private val towerByMarkerId = mutableMapOf<Long, CellDatabase.TowerEstimate>()

    private var selectedScreen = Screen.NETWORK_MODE
    private var isFollowingLocation = true
    private var permissionDialog: AlertDialog? = null
    private var pendingCollectorStart = false
    private var collectorReceiverRegistered = false

    private val collectorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            renderCollectorState()
            when (intent?.action) {
                CellCollectorService.ACTION_SNAPSHOT_COLLECTED -> {
                    renderSnapshot()
                    renderTowerEstimates()
                }

                CellCollectorService.ACTION_LOCATION_UPDATED -> {
                    renderMyLocationMarker(centerMap = isFollowingLocation)
                }
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
        cellDatabase = CellDatabase(applicationContext)
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
        renderTowerEstimates()
        renderMyLocationMarker(centerMap = isFollowingLocation)
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
        cellDatabase.close()
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
        myLocationButton = findViewById(R.id.myLocationButton)
        myLocationButton.isSelected = isFollowingLocation
        towerMapView = findViewById(R.id.towerMapView)
        towerDetailsPanel = findViewById(R.id.towerDetailsPanel)
        towerDetailsTitle = findViewById(R.id.towerDetailsTitle)
        towerDetailsText = findViewById(R.id.towerDetailsText)
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
        myLocationButton.setOnClickListener {
            setLocationFollowingEnabled(true, moveImmediately = true)
        }
        findViewById<Button>(R.id.operatorSettingsButton).setOnClickListener {
            openSettings(Settings.ACTION_NETWORK_OPERATOR_SETTINGS)
        }
        findViewById<Button>(R.id.networkSettingsButton).setOnClickListener {
            openSettings(Settings.ACTION_WIRELESS_SETTINGS)
        }
        findViewById<Button>(R.id.radioInfoButton).setOnClickListener {
            openRadioInfo()
        }
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
            if (screen == Screen.TOWER_MAP) {
                setLocationFollowingEnabled(true, moveImmediately = true)
                renderTowerEstimates()
            }
        }
    }

    private fun renderScreen() {
        networkModeScreen.visibility = if (selectedScreen == Screen.NETWORK_MODE) View.VISIBLE else View.GONE
        towerMapScreen.visibility = if (selectedScreen == Screen.TOWER_MAP) View.VISIBLE else View.GONE
        bottomNavigator.select(selectedScreen)
    }

    /**
     * Initializes the Maptoolkit-backed MapLibre map and tower marker interaction.
     */
    @Suppress("DEPRECATION")
    private fun configureMap() {
        towerMapView.getMapAsync { map ->
            towerMap = map
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(20.0, 0.0))
                .zoom(2.0)
                .build()
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    setLocationFollowingEnabled(false, moveImmediately = false)
                }
            }
            map.setOnMarkerClickListener { marker ->
                val estimate = towerByMarkerId[marker.id] ?: return@setOnMarkerClickListener false
                showTowerDetails(estimate)
                true
            }
            map.addOnMapClickListener {
                towerDetailsPanel.visibility = View.GONE
                false
            }

            if (hasInternetConnection()) {
                map.setStyle(MAP_STYLE_URL) {
                    renderTowerEstimates()
                    renderMyLocationMarker(centerMap = isFollowingLocation)
                }
            } else {
                map.setStyle(Style.Builder().fromJson(OFFLINE_STYLE_JSON)) {
                    renderTowerEstimates()
                    renderMyLocationMarker(centerMap = isFollowingLocation)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun renderTowerEstimates() {
        val map = towerMap ?: return
        if (map.style == null) return

        towerMarkers.forEach { map.removeMarker(it) }
        towerMarkers.clear()
        towerByMarkerId.clear()

        val connectedCellKeys = currentConnectedCellKeys()
        val now = System.currentTimeMillis()
        cellDatabase.readTowerEstimates().forEach { estimate ->
            val markerColor = when {
                estimate.cellKey in connectedCellKeys -> Color.rgb(35, 160, 80)
                now - estimate.lastSeenAt >= STALE_TOWER_AGE_MS -> Color.rgb(210, 55, 55)
                else -> Color.rgb(120, 120, 120)
            }
            val marker = map.addMarker(
                MarkerOptions()
                    .position(LatLng(estimate.latitude, estimate.longitude))
                    .icon(createTowerMarkerIcon(markerColor))
            )
            towerMarkers += marker
            towerByMarkerId[marker.id] = estimate
        }
    }

    private fun setLocationFollowingEnabled(enabled: Boolean, moveImmediately: Boolean) {
        isFollowingLocation = enabled
        myLocationButton.isSelected = enabled
        if (enabled && moveImmediately) {
            renderMyLocationMarker(centerMap = true, useFollowZoom = true)
        }
    }

    @Suppress("DEPRECATION")
    private fun renderMyLocationMarker(
        centerMap: Boolean,
        useFollowZoom: Boolean = false,
    ) {
        val map = towerMap ?: return
        if (map.style == null) return
        val location = GpsLocationStore.read(this)
            ?.takeIf { it.accuracy <= CellCollectorService.MAX_GPS_ACCURACY_METERS }
            ?.takeIf { System.currentTimeMillis() - it.time <= MAX_MAP_LOCATION_AGE_MS }

        if (location == null) {
            if (centerMap) {
                Toast.makeText(this, R.string.map_location_unavailable, Toast.LENGTH_SHORT).show()
            }
            return
        }

        userLocationMarker?.let(map::removeMarker)
        userLocationMarker = map.addMarker(
            MarkerOptions()
                .position(LatLng(location.latitude, location.longitude))
                .title(getString(R.string.map_action_my_location))
        )

        if (centerMap) {
            val currentZoom = map.cameraPosition.zoom
            val targetZoom = if (useFollowZoom) FOLLOW_LOCATION_ZOOM else currentZoom
            map.animateCamera(
                org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(location.latitude, location.longitude))
                        .zoom(targetZoom)
                        .build()
                ),
                LOCATION_CAMERA_ANIMATION_MS,
            )
        }
    }

    private fun currentConnectedCellKeys(): Set<String> {
        return when (val snapshot = cellRepository.readSnapshot()) {
            is CellSnapshot.Available -> snapshot.records
                .asSequence()
                .filter { it.registered }
                .map { it.cellKey }
                .toSet()

            else -> emptySet()
        }
    }

    private fun createTowerMarkerIcon(color: Int) =
        IconFactory.getInstance(this).fromBitmap(
            Bitmap.createBitmap(36, 36, Bitmap.Config.ARGB_8888).apply {
                val canvas = Canvas(this)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(18f, 18f, 13f, paint)
                paint.color = Color.WHITE
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f
                canvas.drawCircle(18f, 18f, 13f, paint)
            }
        )

    private fun hasInternetConnection(): Boolean {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun showTowerDetails(estimate: CellDatabase.TowerEstimate) {
        towerDetailsTitle.text = getString(R.string.map_tower_title, estimate.radio)
        towerDetailsText.text = getString(
            R.string.map_tower_details,
            estimate.operator ?: getString(R.string.common_not_available),
            estimate.sampleCount,
            estimate.radiusMeters,
            estimate.latitude,
            estimate.longitude,
            estimate.identityText,
        )
        towerDetailsPanel.visibility = View.VISIBLE
    }

    private fun registerCollectorReceiver() {
        if (collectorReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(CellCollectorService.ACTION_SNAPSHOT_COLLECTED)
            addAction(CellCollectorService.ACTION_STATE_CHANGED)
            addAction(CellCollectorService.ACTION_LOCATION_UPDATED)
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
     * If required permissions are not available yet, collection starts immediately after
     * the permission request is completed successfully.
     */
    private fun startCollectorOnLaunch() {
        if (!cellRepository.hasRequiredPermissions()) return
        pendingCollectorStart = true
        requestNotificationPermissionForCollector()
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
        when (snapshot) {
            CellSnapshot.PermissionRequired -> renderPermissionRequired()
            CellSnapshot.Unavailable -> renderUnavailable()
            is CellSnapshot.Available -> renderAvailable(snapshot)
        }
    }

    private fun renderPermissionRequired() {
        statusText.setText(R.string.main_status_permission_required)
        networkSummaryText.text = getString(R.string.main_permissions_text)
    }

    private fun renderUnavailable() {
        statusText.setText(R.string.main_status_no_telephony)
        networkSummaryText.text = ""
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
            Toast.makeText(
                this,
                error.localizedMessage ?: getString(R.string.main_system_menu_not_found),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action))
        } catch (error: ActivityNotFoundException) {
            Toast.makeText(this, action, Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val KEY_SELECTED_SCREEN = "selected_screen"
        const val MAP_STYLE_URL = "https://styles.maptoolkit.org/summer.json"
        const val MAX_MAP_LOCATION_AGE_MS = 30_000L
        const val FOLLOW_LOCATION_ZOOM = 16.0
        const val LOCATION_CAMERA_ANIMATION_MS = 900
        const val STALE_TOWER_AGE_MS = 30L * 24L * 60L * 60L * 1000L
        const val OFFLINE_STYLE_JSON = """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#e7e7e7"}}]}"""
    }
}
