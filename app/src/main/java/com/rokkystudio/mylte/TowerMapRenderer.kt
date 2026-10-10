package com.rokkystudio.mylte

/**
 * Builds a local HTML map document for cellular records that include coordinates.
 */
class TowerMapRenderer {
    fun render(records: List<CellTowerRecord>): String {
        val markers = records
            .filter { it.hasCoordinates() }
            .joinToString(separator = "\n") { record ->
                val lat = record.latitude ?: 0.0
                val lon = record.longitude ?: 0.0
                "L.marker([$lat, $lon]).addTo(map).bindPopup('${escape(record.title())}');"
            }
        val first = records.firstOrNull { it.hasCoordinates() }
        val centerLat = first?.latitude ?: 0.0
        val centerLon = first?.longitude ?: 0.0
        val zoom = if (first == null) 2 else 13
        return """
            <!doctype html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
                <style>
                    html, body, #map { width: 100%; height: 100%; margin: 0; background: #111318; }
                    .leaflet-container { background: #111318; }
                </style>
            </head>
            <body>
                <div id="map"></div>
                <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
                <script>
                    const map = L.map('map').setView([$centerLat, $centerLon], $zoom);
                    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19 }).addTo(map);
                    $markers
                </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun escape(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", " ")
    }
}