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
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
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
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Polygon
import org.maplibre.android.annotations.PolygonOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.gestures.RotateGestureDetector
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.text.DateFormat
import java.util.Date

/**
 * Shows network controls, cellular collector state, current map position, coverage cells, and cached tower estimates.
 */
class MainActivity : AppCompatActivity() {
    private enum class Screen {
        NETWORK_MODE,
        TOWER_MAP,
        COVERAGE_LIST,
    }

    private lateinit var uiSettings: UiSettings
    private lateinit var cellRepository: CellRepository
    private lateinit var cellDatabase: CellDatabase
    private lateinit var mapLocationManager: LocationManager
    private lateinit var neoTopBar: NeoTopBarView
    private lateinit var bottomNavigator: NeoScreenNavigatorView
    private lateinit var networkModeScreen: View
    private lateinit var towerMapScreen: View
    private lateinit var statusText: TextView
    private lateinit var networkSummaryText: TextView
    private lateinit var collectorStatusText: TextView
    private lateinit var collectorToggleButton: Button
    private lateinit var myLocationButton: ImageButton
    private lateinit var northUpButton: ImageButton
    private lateinit var coverageListButton: ImageButton
    private lateinit var coverageListCloseButton: ImageButton
    private lateinit var coverageListPanel: View
    private lateinit var mapCoverageLegend: LinearLayout
    private lateinit var connectedCellsLegend: LinearLayout
    private lateinit var towerMapView: MapView
    private lateinit var towerDetailsPanel: View
    private lateinit var towerDetailsTitle: TextView
    private lateinit var towerDetailsText: TextView

    private var towerMap: MapLibreMap? = null
    private var userLocationMarker: Marker? = null
    private val towerMarkers = mutableListOf<Marker>()
    private val coveragePolygons = mutableListOf<Polygon>()
    private val towerByMarkerId = mutableMapOf<Long, CellDatabase.TowerEstimate>()
    private val hiddenCoverageCellKeys = mutableSetOf<String>()
    private val connectedSignalTextByCellKey = mutableMapOf<String, TextView>()
    private val connectedSignalIconByCellKey = mutableMapOf<String, ImageView>()
    private var renderedConnectedCellKeys = emptySet<String>()

    private var selectedScreen = Screen.NETWORK_MODE
    private var isFollowingLocation = true
    private var isNorthUp = true
    private var isCoverageListVisible = false
    private var hasFollowCentered = false
    private val movementHeadingTracker = MovementHeadingTracker()
    private var lastMovementBearingDegrees: Float? = null
    private var latestMapLocation: Location? = null
    private var mapLocationUpdatesRegistered = false
    private var trackingMapTouch = false
    private var mapGestureReported = false
    private var mapTouchDownX = 0f
    private var mapTouchDownY = 0f
    private var permissionDialog: AlertDialog? = null
    private var pendingCollectorStart = false
    private var collectorReceiverRegistered = false
    private val uiRefreshHandler = Handler(Looper.getMainLooper())
    private val signalUiRefresh = object : Runnable {
        override fun run() {
            if (isCoverageListVisible) {
                refreshConnectedSignalUi()
                uiRefreshHandler.postDelayed(this, SIGNAL_UI_REFRESH_INTERVAL_MS)
            }
        }
    }

    private val mapLocationListener = LocationListener { location ->
        if (!isUsableMapLocation(location)) return@LocationListener
        latestMapLocation = Location(location)
        movementHeadingTracker.update(location)?.let { bearing ->
            lastMovementBearingDegrees = bearing
        }
        if (selectedScreen == Screen.TOWER_MAP) {
            renderMyLocationMarker(centerMap = isFollowingLocation)
        }
    }

    private val collectorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            renderCollectorState()
            when (intent?.action) {
                CellCollectorService.ACTION_SNAPSHOT_COLLECTED -> {
                    renderSnapshot()
                    renderTowerEstimates()
                    renderConnectedCellsLegend()
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
        mapLocationManager = getSystemService(LocationManager::class.java)
        selectedScreen = savedInstanceState?.getString(KEY_SELECTED_SCREEN)
            ?.let { runCatching { Screen.valueOf(it) }.getOrNull() }
            ?: Screen.NETWORK_MODE
        isCoverageListVisible = selectedScreen == Screen.COVERAGE_LIST
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
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (selectedScreen == Screen.COVERAGE_LIST) {
                        setCoverageListVisible(false)
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }


    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::towerMapView.isInitialized && towerMapScreen.visibility == View.VISIBLE) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    trackingMapTouch = isTouchInsideMap(event.rawX, event.rawY)
                    mapGestureReported = false
                    mapTouchDownX = event.rawX
                    mapTouchDownY = event.rawY
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (trackingMapTouch && !mapGestureReported) {
                        mapGestureReported = true
                        setLocationFollowingEnabled(false, moveImmediately = false)
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (trackingMapTouch && !mapGestureReported) {
                        val deltaX = event.rawX - mapTouchDownX
                        val deltaY = event.rawY - mapTouchDownY
                        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
                        if (deltaX * deltaX + deltaY * deltaY >= touchSlop * touchSlop) {
                            mapGestureReported = true
                            setLocationFollowingEnabled(false, moveImmediately = false)
                        }
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    trackingMapTouch = false
                    mapGestureReported = false
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun isTouchInsideMap(rawX: Float, rawY: Float): Boolean {
        if (!isTouchInsideView(towerMapView, rawX, rawY)) return false
        if (isTouchInsideView(myLocationButton, rawX, rawY)) return false
        if (isTouchInsideView(northUpButton, rawX, rawY)) return false
        if (isTouchInsideView(coverageListButton, rawX, rawY)) return false
        if (coverageListPanel.visibility == View.VISIBLE &&
            isTouchInsideView(coverageListPanel, rawX, rawY)
        ) {
            return false
        }
        if (towerDetailsPanel.visibility == View.VISIBLE &&
            isTouchInsideView(towerDetailsPanel, rawX, rawY)
        ) {
            return false
        }
        return true
    }

    private fun isTouchInsideView(view: View, rawX: Float, rawY: Float): Boolean {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return rawX >= location[0] &&
            rawX < location[0] + view.width &&
            rawY >= location[1] &&
            rawY < location[1] + view.height
    }

    override fun onStart() {
        super.onStart()
        towerMapView.onStart()
        registerCollectorReceiver()
    }

    override fun onResume() {
        super.onResume()
        towerMapView.onResume()
        startMapLocationUpdates()
        renderCollectorState()
        renderSnapshot()
        renderTowerEstimates()
        renderConnectedCellsLegend()
        restartSignalUiRefresh()
        renderMyLocationMarker(centerMap = isFollowingLocation)
    }

    override fun onPause() {
        stopSignalUiRefresh()
        stopMapLocationUpdates()
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
        northUpButton = findViewById(R.id.northUpButton)
        coverageListButton = findViewById(R.id.coverageListButton)
        coverageListCloseButton = findViewById(R.id.coverageListCloseButton)
        coverageListPanel = findViewById(R.id.coverageListPanel)
        mapCoverageLegend = findViewById(R.id.mapCoverageLegend)
        connectedCellsLegend = findViewById(R.id.connectedCellsLegend)
        myLocationButton.isSelected = isFollowingLocation
        northUpButton.isSelected = isNorthUp
        coverageListButton.isSelected = isCoverageListVisible
        coverageListPanel.visibility = if (isCoverageListVisible) View.VISIBLE else View.GONE
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
        northUpButton.setOnClickListener {
            setNorthUpEnabled(
                enabled = !isNorthUp,
                animateToNorth = !isNorthUp,
            )
        }
        coverageListButton.setOnClickListener {
            setCoverageListVisible(true)
        }
        coverageListCloseButton.setOnClickListener {
            setCoverageListVisible(false)
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
            selectedValue = if (selectedScreen == Screen.COVERAGE_LIST) {
                Screen.TOWER_MAP
            } else {
                selectedScreen
            },
            placement = NeoScreenNavigatorView.Placement.BOTTOM,
        ) { screen ->
            selectedScreen = screen
            renderScreen()
            renderSnapshot()
            if (screen == Screen.TOWER_MAP) {
                hasFollowCentered = false
                setLocationFollowingEnabled(true, moveImmediately = true)
                renderTowerEstimates()
                renderConnectedCellsLegend()
            }
        }
    }

    private fun renderScreen() {
        networkModeScreen.visibility =
            if (selectedScreen == Screen.NETWORK_MODE) View.VISIBLE else View.GONE
        towerMapScreen.visibility =
            if (selectedScreen == Screen.TOWER_MAP) View.VISIBLE else View.GONE
        coverageListPanel.visibility =
            if (selectedScreen == Screen.COVERAGE_LIST) View.VISIBLE else View.GONE
        bottomNavigator.visibility =
            if (selectedScreen == Screen.COVERAGE_LIST) View.GONE else View.VISIBLE

        if (selectedScreen != Screen.COVERAGE_LIST) {
            bottomNavigator.select(selectedScreen)
        }
    }

    /**
     * Initializes the Maptoolkit-backed MapLibre map and tower marker interaction.
     */
    @Suppress("DEPRECATION")
    private fun configureMap() {
        towerMapView.getMapAsync { map ->
            towerMap = map
            map.uiSettings.setRotateGesturesEnabled(true)
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(20.0, 0.0))
                .zoom(2.0)
                .build()
            map.addOnRotateListener(
                object : MapLibreMap.OnRotateListener {
                    override fun onRotateBegin(detector: RotateGestureDetector) {
                        if (isNorthUp) {
                            setNorthUpEnabled(
                                enabled = false,
                                animateToNorth = false,
                            )
                        }
                    }

                    override fun onRotate(detector: RotateGestureDetector) {
                        renderMyLocationMarker(centerMap = false)
                    }

                    override fun onRotateEnd(detector: RotateGestureDetector) {
                    }
                }
            )
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

    private data class CoverageGridKey(
        val south: Double,
        val west: Double,
        val north: Double,
        val east: Double,
    )

    private data class CoverageLegendItem(
        val cellKey: String,
        val radio: String,
        val operator: String?,
        val identityText: String,
        val connected: Boolean,
        val hasCoverage: Boolean,
        val signalDbm: Int?,
        val signalLevel: Int?,
    )

    @Suppress("DEPRECATION")
    private fun renderTowerEstimates() {
        val map = towerMap ?: return
        if (map.style == null) return

        coveragePolygons.forEach { map.removePolygon(it) }
        coveragePolygons.clear()
        towerMarkers.forEach { map.removeMarker(it) }
        towerMarkers.clear()
        towerByMarkerId.clear()

        val connectedCellKeys = currentConnectedCellKeys()
        val visibleCoverage = cellDatabase.readCoverageCells()
            .filter { it.cellKey !in hiddenCoverageCellKeys }

        visibleCoverage
            .groupBy {
                CoverageGridKey(
                    south = it.south,
                    west = it.west,
                    north = it.north,
                    east = it.east,
                )
            }
            .forEach { (grid, cells) ->
                val connectedCells = cells.filter { it.cellKey in connectedCellKeys }
                val renderedCells = connectedCells.ifEmpty { cells }
                val color = blendTowerColors(renderedCells.map { towerColor(it.cellKey) })
                val alpha = renderedCells.maxOf { coverageAlpha(it.averageSignalDbm) }
                val polygon = map.addPolygon(
                    PolygonOptions()
                        .add(
                            LatLng(grid.south, grid.west),
                            LatLng(grid.south, grid.east),
                            LatLng(grid.north, grid.east),
                            LatLng(grid.north, grid.west),
                        )
                        .fillColor(color)
                        .strokeColor(Color.TRANSPARENT)
                        .alpha(alpha)
                )
                coveragePolygons += polygon
            }

        cellDatabase.readTowerEstimates()
            .asSequence()
            .filter { it.cellKey !in hiddenCoverageCellKeys }
            .sortedBy { it.cellKey in connectedCellKeys }
            .forEach { estimate ->
                val marker = map.addMarker(
                    MarkerOptions()
                        .position(LatLng(estimate.latitude, estimate.longitude))
                        .icon(createTowerMarkerIcon(towerColor(estimate.cellKey)))
                )
                towerMarkers += marker
                towerByMarkerId[marker.id] = estimate
            }
    }

    private fun renderConnectedCellsLegend() {
        connectedCellsLegend.removeAllViews()
        connectedSignalTextByCellKey.clear()
        connectedSignalIconByCellKey.clear()

        val connectedRecords = when (val snapshot = cellRepository.readSnapshot()) {
            is CellSnapshot.Available -> snapshot.records
                .filter { it.registered && it.persistable }
                .distinctBy { it.cellKey }

            else -> emptyList()
        }
        val connectedByKey = connectedRecords.associateBy { it.cellKey }
        renderedConnectedCellKeys = connectedByKey.keys
        val coverageByKey = cellDatabase.readCoverageCells()
            .groupBy { it.cellKey }

        val legendItems = linkedMapOf<String, CoverageLegendItem>()
        coverageByKey.forEach { (cellKey, cells) ->
            val first = cells.first()
            legendItems[cellKey] = CoverageLegendItem(
                cellKey = cellKey,
                radio = first.radio,
                operator = first.operator,
                identityText = first.identityText,
                connected = cellKey in connectedByKey,
                hasCoverage = true,
                signalDbm = null,
                signalLevel = null,
            )
        }
        connectedRecords.forEach { record ->
            legendItems[record.cellKey] = CoverageLegendItem(
                cellKey = record.cellKey,
                radio = record.radio,
                operator = record.operator,
                identityText = record.identityLines.joinToString("\n"),
                connected = true,
                hasCoverage = record.cellKey in coverageByKey,
                signalDbm = record.signalDbm.takeIf { it in -200..-1 },
                signalLevel = record.signalLevel.takeIf { it in 0..4 },
            )
        }

        val sortedItems = legendItems.values.sortedWith(
            compareByDescending<CoverageLegendItem> { it.connected }
                .thenBy { it.radio }
                .thenBy { it.cellKey }
        )
        renderMapCoverageLegend(sortedItems)
        if (sortedItems.isEmpty() && isCoverageListVisible) {
            coverageListPanel.visibility = View.VISIBLE
        }

        sortedItems.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                if (connectedCellsLegend.childCount > 0) {
                    setPadding(0, dp(6), 0, 0)
                }
            }

            val visibilityButton = ImageButton(this).apply {
                val visible = item.cellKey !in hiddenCoverageCellKeys
                setImageResource(
                    if (visible) R.drawable.ic_visibility else R.drawable.ic_visibility_off
                )
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                isEnabled = item.hasCoverage
                alpha = if (item.hasCoverage) 1f else 0.35f
                contentDescription = getString(
                    if (visible) {
                        R.string.map_coverage_hide
                    } else {
                        R.string.map_coverage_show
                    },
                    item.radio,
                    item.operator ?: getString(R.string.common_not_available),
                )
                setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                setOnClickListener {
                    if (item.cellKey in hiddenCoverageCellKeys) {
                        hiddenCoverageCellKeys.remove(item.cellKey)
                    } else {
                        hiddenCoverageCellKeys.add(item.cellKey)
                    }
                    renderTowerEstimates()
                    renderConnectedCellsLegend()
                }
            }
            row.addView(
                visibilityButton,
                LinearLayout.LayoutParams(dp(40), dp(40)),
            )

            row.addView(
                View(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(towerColor(item.cellKey))
                    }
                },
                LinearLayout.LayoutParams(dp(14), dp(14)).apply {
                    marginEnd = dp(8)
                },
            )

            val networkText = TextView(this).apply {
                text = buildString {
                    append(item.radio)
                    item.operator?.takeIf { it.isNotBlank() }?.let {
                        append(" · ").append(it)
                    }
                    item.identityText.lineSequence()
                        .firstOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { append("\n").append(it) }
                    if (!item.hasCoverage) {
                        append("\n").append(getString(R.string.map_coverage_no_saved_data))
                    }
                }
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 12f
                includeFontPadding = false
            }
            row.addView(
                networkText,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )

            val statusColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                visibility = if (item.connected) View.VISIBLE else View.INVISIBLE
            }
            statusColumn.addView(
                TextView(this).apply {
                    text = getString(R.string.map_coverage_connected)
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                    textSize = 10f
                    includeFontPadding = false
                    gravity = android.view.Gravity.CENTER
                },
                LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            val signalText = TextView(this).apply {
                text = item.signalDbm?.let { "$it dBm" } ?: ""
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 11f
                includeFontPadding = false
                gravity = android.view.Gravity.CENTER
            }
            statusColumn.addView(
                signalText,
                LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            if (item.connected) {
                connectedSignalTextByCellKey[item.cellKey] = signalText
            }
            row.addView(
                statusColumn,
                LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            if (item.connected) {
                val signalIcon = ImageView(this).apply {
                    setImageResource(signalLevelIcon(item.signalLevel))
                    setColorFilter(
                        ContextCompat.getColor(this@MainActivity, R.color.text_primary)
                    )
                    contentDescription = getString(R.string.map_coverage_connected)
                }
                connectedSignalIconByCellKey[item.cellKey] = signalIcon
                row.addView(
                    signalIcon,
                    LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                        marginStart = dp(8)
                        marginEnd = dp(5)
                    },
                )
            } else {
                row.addView(
                    View(this),
                    LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                        marginStart = dp(8)
                        marginEnd = dp(5)
                    },
                )
            }

            connectedCellsLegend.addView(row)
        }
    }

    private fun renderMapCoverageLegend(items: List<CoverageLegendItem>) {
        mapCoverageLegend.removeAllViews()
        val visibleItems = items.filter { it.cellKey !in hiddenCoverageCellKeys }
        mapCoverageLegend.visibility = if (visibleItems.isEmpty()) View.GONE else View.VISIBLE

        visibleItems.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                if (mapCoverageLegend.childCount > 0) {
                    setPadding(0, dp(4), 0, 0)
                }
            }
            row.addView(
                View(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(towerColor(item.cellKey))
                    }
                },
                LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                    marginEnd = dp(6)
                },
            )
            row.addView(
                TextView(this).apply {
                    text = buildString {
                        append(item.radio)
                        item.identityText.lineSequence()
                            .firstOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?.let { append(" · ").append(it) }
                    }
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                    textSize = 11f
                    includeFontPadding = false
                }
            )
            mapCoverageLegend.addView(row)
        }
    }

    private fun setCoverageListVisible(visible: Boolean) {
        isCoverageListVisible = visible
        coverageListButton.isSelected = visible
        selectedScreen = if (visible) Screen.COVERAGE_LIST else Screen.TOWER_MAP
        renderScreen()

        if (visible) {
            towerDetailsPanel.visibility = View.GONE
            renderConnectedCellsLegend()
            restartSignalUiRefresh()
        } else {
            stopSignalUiRefresh()
            renderTowerEstimates()
            renderMyLocationMarker(centerMap = isFollowingLocation)
        }
    }

    private fun refreshConnectedSignalUi() {
        val connectedRecords = when (val snapshot = cellRepository.readSnapshot()) {
            is CellSnapshot.Available -> snapshot.records
                .filter { it.registered && it.persistable }
                .distinctBy { it.cellKey }

            else -> emptyList()
        }
        val currentKeys = connectedRecords.mapTo(mutableSetOf()) { it.cellKey }
        if (currentKeys != renderedConnectedCellKeys) {
            renderConnectedCellsLegend()
            return
        }

        connectedRecords.forEach { record ->
            connectedSignalTextByCellKey[record.cellKey]?.text =
                record.signalDbm.takeIf { it in -200..-1 }?.let { "$it dBm" } ?: ""
            connectedSignalIconByCellKey[record.cellKey]?.setImageResource(
                signalLevelIcon(record.signalLevel.takeIf { it in 0..4 })
            )
        }
    }

    private fun restartSignalUiRefresh() {
        stopSignalUiRefresh()
        if (isCoverageListVisible) {
            uiRefreshHandler.postDelayed(signalUiRefresh, SIGNAL_UI_REFRESH_INTERVAL_MS)
        }
    }

    private fun stopSignalUiRefresh() {
        uiRefreshHandler.removeCallbacks(signalUiRefresh)
    }

    private fun currentConnectedCellKeys(): Set<String> {
        return when (val snapshot = cellRepository.readSnapshot()) {
            is CellSnapshot.Available -> snapshot.records
                .asSequence()
                .filter { it.registered && it.persistable }
                .map { it.cellKey }
                .toSet()

            else -> emptySet()
        }
    }

    private fun signalLevelIcon(level: Int?): Int {
        return when (level) {
            0 -> R.drawable.ic_signal_bars_0
            1 -> R.drawable.ic_signal_bars_1
            2 -> R.drawable.ic_signal_bars_2
            3 -> R.drawable.ic_signal_bars_3
            4 -> R.drawable.ic_signal_bars_4
            else -> R.drawable.ic_signal_bars_0
        }
    }

    private fun blendTowerColors(colors: List<Int>): Int {
        if (colors.isEmpty()) return Color.TRANSPARENT
        val red = colors.sumOf { Color.red(it) } / colors.size
        val green = colors.sumOf { Color.green(it) } / colors.size
        val blue = colors.sumOf { Color.blue(it) } / colors.size
        return Color.rgb(red, green, blue)
    }

    private fun towerColor(cellKey: String): Int {
        val index = Math.floorMod(cellKey.hashCode(), TOWER_COLOR_PALETTE.size)
        return TOWER_COLOR_PALETTE[index]
    }

    private fun coverageAlpha(signalDbm: Double): Float {
        val normalized = ((signalDbm - COVERAGE_SIGNAL_MIN_DBM) /
            (COVERAGE_SIGNAL_MAX_DBM - COVERAGE_SIGNAL_MIN_DBM))
            .coerceIn(0.0, 1.0)
        return (COVERAGE_MIN_ALPHA +
            normalized * (COVERAGE_MAX_ALPHA - COVERAGE_MIN_ALPHA)).toFloat()
    }

    private fun setNorthUpEnabled(
        enabled: Boolean,
        animateToNorth: Boolean,
    ) {
        isNorthUp = enabled
        northUpButton.isSelected = enabled
        northUpButton.contentDescription = getString(
            if (enabled) R.string.map_action_north_up else R.string.map_action_heading_up
        )
        towerMap?.uiSettings?.setRotateGesturesEnabled(true)

        if (enabled && animateToNorth) {
            animateMapBearingToNorth()
        } else {
            renderMyLocationMarker(centerMap = false)
        }
    }

    private fun animateMapBearingToNorth() {
        val map = towerMap ?: return
        val cameraPosition = map.cameraPosition
        map.animateCamera(
            org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder(cameraPosition)
                    .bearing(0.0)
                    .build()
            ),
            NORTH_UP_ANIMATION_MS,
            object : MapLibreMap.CancelableCallback {
                override fun onFinish() {
                    renderMyLocationMarker(centerMap = false)
                }

                override fun onCancel() {
                    renderMyLocationMarker(centerMap = false)
                }
            },
        )
    }

    private fun setLocationFollowingEnabled(enabled: Boolean, moveImmediately: Boolean) {
        isFollowingLocation = enabled
        myLocationButton.isSelected = enabled
        if (enabled && moveImmediately) {
            hasFollowCentered = false
            renderMyLocationMarker(centerMap = true)
        }
    }

    @Suppress("DEPRECATION")
    private fun renderMyLocationMarker(centerMap: Boolean) {
        val map = towerMap ?: return
        if (map.style == null) return
        val location = latestMapLocation
            ?.takeIf(::isUsableMapLocation)
            ?: readBestLastKnownMapLocation()

        if (location == null) {
            if (centerMap) {
                Toast.makeText(this, R.string.map_location_unavailable, Toast.LENGTH_SHORT).show()
            }
            return
        }

        movementHeadingTracker.update(location)?.let { bearing ->
            lastMovementBearingDegrees = bearing
        }

        val movementBearing = lastMovementBearingDegrees
        val markerRotation = when {
            movementBearing == null -> 0f
            isFollowingLocation && !isNorthUp -> 0f
            else -> normalizeBearing(
                movementBearing - map.cameraPosition.bearing.toFloat()
            )
        }

        val markerIcon = createNavigationMarkerIcon(markerRotation)
        val marker = userLocationMarker
        if (marker == null) {
            userLocationMarker = map.addMarker(
                MarkerOptions()
                    .position(LatLng(location.latitude, location.longitude))
                    .icon(markerIcon)
            )
        } else {
            marker.position = LatLng(location.latitude, location.longitude)
            marker.icon = markerIcon
            map.updateMarker(marker)
        }

        if (!centerMap || !isFollowingLocation) return

        val shouldApplyFollowZoom = !hasFollowCentered
        val targetZoom = if (shouldApplyFollowZoom) {
            FOLLOW_LOCATION_ZOOM
        } else {
            map.cameraPosition.zoom
        }
        val targetBearing = when {
            isNorthUp -> 0.0
            movementBearing != null -> movementBearing.toDouble()
            else -> map.cameraPosition.bearing
        }

        map.animateCamera(
            org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(location.latitude, location.longitude))
                    .zoom(targetZoom)
                    .bearing(targetBearing)
                    .build()
            ),
            LOCATION_CAMERA_ANIMATION_MS,
        )
        if (shouldApplyFollowZoom) {
            hasFollowCentered = true
        }
    }

    private fun normalizeBearing(value: Float): Float {
        return ((value % 360f) + 360f) % 360f
    }

    private fun createNavigationMarkerIcon(rotationDegrees: Float) =
        IconFactory.getInstance(this).fromBitmap(
            Bitmap.createBitmap(dp(44), dp(44), Bitmap.Config.ARGB_8888).apply {
                val canvas = Canvas(this)
                val center = width / 2f
                canvas.rotate(rotationDegrees, center, center)

                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(33, 150, 243)
                    style = Paint.Style.FILL
                }
                val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = dp(2).toFloat()
                    strokeJoin = Paint.Join.ROUND
                }
                val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(80, 0, 0, 0)
                    style = Paint.Style.FILL
                }

                val path = android.graphics.Path().apply {
                    moveTo(center, dp(4).toFloat())
                    lineTo(dp(36).toFloat(), dp(36).toFloat())
                    lineTo(center, dp(29).toFloat())
                    lineTo(dp(8).toFloat(), dp(36).toFloat())
                    close()
                }

                canvas.save()
                canvas.translate(0f, dp(2).toFloat())
                canvas.drawPath(path, shadow)
                canvas.restore()
                canvas.drawPath(path, fill)
                canvas.drawPath(path, outline)
            }
        )

    private fun startMapLocationUpdates() {
        val fine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return

        latestMapLocation = readBestLastKnownMapLocation() ?: latestMapLocation
        if (mapLocationUpdatesRegistered) return

        val providers = buildList {
            if (fine) add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }.distinct()

        providers.forEach { provider ->
            runCatching {
                mapLocationManager.requestLocationUpdates(
                    provider,
                    MAP_LOCATION_UPDATE_INTERVAL_MS,
                    0f,
                    mapLocationListener,
                    Looper.getMainLooper(),
                )
            }
        }
        mapLocationUpdatesRegistered = true
    }

    private fun stopMapLocationUpdates() {
        if (!mapLocationUpdatesRegistered) return
        mapLocationManager.removeUpdates(mapLocationListener)
        mapLocationUpdatesRegistered = false
    }

    private fun readBestLastKnownMapLocation(): Location? {
        val fine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null

        return mapLocationManager.allProviders
            .asSequence()
            .mapNotNull { provider ->
                runCatching { mapLocationManager.getLastKnownLocation(provider) }.getOrNull()
            }
            .filter(::isUsableMapLocation)
            .maxWithOrNull(
                compareBy<Location> { it.time }
                    .thenBy { -it.accuracy }
            )
            ?.let(::Location)
    }

    private fun isUsableMapLocation(location: Location): Boolean {
        return location.latitude.isFinite() &&
            location.longitude.isFinite() &&
            System.currentTimeMillis() - location.time <= MAX_MAP_LOCATION_AGE_MS
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
        setCoverageListVisible(false)

        val gridCount = cellDatabase.readTowerGridCount(estimate.cellKey)
        val dateTimeFormat = DateFormat.getDateTimeInstance(
            DateFormat.SHORT,
            DateFormat.MEDIUM,
        )
        val recentSamples = cellDatabase.readTowerRecentSamples(
            cellKey = estimate.cellKey,
            limit = TOWER_DETAILS_HISTORY_LIMIT,
        )
        val recentHistory = recentSamples.joinToString(separator = "\n") { sample ->
            getString(
                R.string.map_tower_history_entry,
                dateTimeFormat.format(Date(sample.recordedAt)),
                sample.signalDbm,
            )
        }.ifBlank {
            getString(R.string.common_not_available)
        }

        towerDetailsTitle.text = getString(R.string.map_tower_title, estimate.radio)
        towerDetailsText.text = getString(
            R.string.map_tower_details,
            estimate.operator ?: getString(R.string.common_not_available),
            estimate.sampleCount,
            gridCount,
            estimate.latitude,
            estimate.longitude,
            dateTimeFormat.format(Date(estimate.lastSeenAt)),
            estimate.identityText,
            recentHistory,
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

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private companion object {
        const val KEY_SELECTED_SCREEN = "selected_screen"
        const val MAP_STYLE_URL = "https://styles.maptoolkit.org/summer.json"
        const val MAX_MAP_LOCATION_AGE_MS = 10L * 60L * 1000L
        const val MAP_LOCATION_UPDATE_INTERVAL_MS = 1_000L
        const val FOLLOW_LOCATION_ZOOM = 17.5
        const val LOCATION_CAMERA_ANIMATION_MS = 1_500
        const val NORTH_UP_ANIMATION_MS = 700
        const val SIGNAL_UI_REFRESH_INTERVAL_MS = 1_000L
        const val TOWER_DETAILS_HISTORY_LIMIT = 3
        const val COVERAGE_SIGNAL_MIN_DBM = -120.0
        const val COVERAGE_SIGNAL_MAX_DBM = -70.0
        const val COVERAGE_MIN_ALPHA = 0.10
        const val COVERAGE_MAX_ALPHA = 0.72
        val TOWER_COLOR_PALETTE = intArrayOf(
            Color.rgb(33, 150, 243),
            Color.rgb(156, 39, 176),
            Color.rgb(255, 152, 0),
            Color.rgb(0, 150, 136),
            Color.rgb(233, 30, 99),
            Color.rgb(63, 81, 181),
            Color.rgb(205, 220, 57),
            Color.rgb(121, 85, 72),
            Color.rgb(0, 188, 212),
            Color.rgb(255, 87, 34),
            Color.rgb(103, 58, 183),
            Color.rgb(139, 195, 74),
        )
        const val OFFLINE_STYLE_JSON = """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#e7e7e7"}}]}"""
    }
}
