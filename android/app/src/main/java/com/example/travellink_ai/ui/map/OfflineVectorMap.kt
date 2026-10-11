package com.example.travellink_ai.ui.map

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.travellink_ai.data.model.Stop
import com.example.travellink_ai.util.OfflineBasemap
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val SRC_ROUTE = "trip-route"
private const val SRC_STOPS = "trip-stops"
private const val SRC_ME = "me"
private const val ACCENT = "#2A6B5E"   // 與線上地圖折線同色（DesignTokens.Accent）

/**
 * 離線互動地圖：MapLibre 讀本機台東 PMTiles（[OfflineBasemap]），
 * 疊上 Room 已存的道路折線、站點序號與目前位置。全程 0 網路。
 *
 * 中文字用手機內建字型畫（localIdeographFontFamily），只有英數字需要 assets 裡的字形檔。
 */
@Composable
fun OfflineVectorMap(
    stops: List<Stop>,
    roadSegments: List<List<com.google.android.gms.maps.model.LatLng>>,
    userLocation: com.google.android.gms.maps.model.LatLng?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context, MapLibreMapOptions.createFromAttributes(context)
            .localIdeographFontFamily("sans-serif")
            .attributionEnabled(true)
            .logoEnabled(false)
        ).apply { onCreate(null) }
    }
    var style by remember { mutableStateOf<Style?>(null) }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        // 進來時 Activity 早已 RESUMED，觀察者不會補發事件，手動跟上
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            mapView.onPause(); mapView.onStop(); mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            map.uiSettings.isRotateGesturesEnabled = false
            map.uiSettings.isTiltGesturesEnabled = false
            map.setMaxZoomPreference(18.0)
            val (w, s, e, n) = OfflineBasemap.BOUNDS.toList()
            map.setLatLngBoundsForCameraTarget(LatLngBounds.from(n, e, s, w))
            map.setStyle(Style.Builder().fromJson(styleJson(context))) { st ->
                addTripLayers(st)
                fitToTrip(map, stops, roadSegments)
                style = st
            }
        }
    }

    // 路線與站點（行程在離線期間不會變，但編輯後回來要跟上）
    LaunchedEffect(style, stops, roadSegments) {
        val st = style ?: return@LaunchedEffect
        st.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.setGeoJson(routeFeatures(stops, roadSegments))
        st.getSourceAs<GeoJsonSource>(SRC_STOPS)?.setGeoJson(stopFeatures(stops))
    }
    LaunchedEffect(style, userLocation) {
        val st = style ?: return@LaunchedEffect
        val me = userLocation?.let {
            FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)))
        } ?: FeatureCollection.fromFeatures(emptyList())
        st.getSourceAs<GeoJsonSource>(SRC_ME)?.setGeoJson(me)
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun styleJson(context: Context): String =
    context.assets.open("offline_map/style.json").bufferedReader().use { it.readText() }
        .replace("{PMTILES_URI}", "file://" + OfflineBasemap.file(context).absolutePath)

private fun addTripLayers(st: Style) {
    st.addSource(GeoJsonSource(SRC_ROUTE))
    st.addSource(GeoJsonSource(SRC_STOPS))
    st.addSource(GeoJsonSource(SRC_ME))
    st.addLayer(LineLayer("trip-route-casing", SRC_ROUTE).withProperties(
        PropertyFactory.lineColor("#FFFFFF"), PropertyFactory.lineWidth(8f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
    ))
    st.addLayer(LineLayer("trip-route", SRC_ROUTE).withProperties(
        PropertyFactory.lineColor(ACCENT), PropertyFactory.lineWidth(5f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
    ))
    st.addLayer(CircleLayer("trip-stops", SRC_STOPS).withProperties(
        PropertyFactory.circleRadius(12f), PropertyFactory.circleColor(ACCENT),
        PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2.5f)
    ))
    st.addLayer(SymbolLayer("trip-stops-num", SRC_STOPS).withProperties(
        PropertyFactory.textField(Expression.get("n")),
        PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
        PropertyFactory.textSize(12f), PropertyFactory.textColor("#FFFFFF"),
        PropertyFactory.textAllowOverlap(true), PropertyFactory.textIgnorePlacement(true)
    ))
    st.addLayer(CircleLayer("me-halo", SRC_ME).withProperties(
        PropertyFactory.circleRadius(20f), PropertyFactory.circleColor("#3B82F6"), PropertyFactory.circleOpacity(0.2f)
    ))
    st.addLayer(CircleLayer("me", SRC_ME).withProperties(
        PropertyFactory.circleRadius(8f), PropertyFactory.circleColor("#3B82F6"),
        PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(3f)
    ))
}

/** 有道路折線就畫折線，沒有退回站點直線（與線上地圖、靜態草圖同一規則） */
private fun routeFeatures(
    stops: List<Stop>, roadSegments: List<List<com.google.android.gms.maps.model.LatLng>>
): FeatureCollection {
    val pts = stops.map { s -> s.lat?.let { la -> s.lng?.let { ln -> com.google.android.gms.maps.model.LatLng(la, ln) } } }
    // 逐段判斷：有道路折線就用，這段沒有（沒網路時查不到、也沒有快取）就畫兩站之間的直線。
    // 過去只在「整份清單是空的」才退回直線，沒網路時拿到的是「每段都是空的」清單，結果什麼都沒畫。
    val segs = if (roadSegments.size == stops.size - 1) {
        roadSegments.mapIndexed { i, seg -> seg.ifEmpty { listOfNotNull(pts[i], pts[i + 1]) } }
    } else if (roadSegments.any { it.size >= 2 }) roadSegments
    else listOf(pts.filterNotNull())
    return FeatureCollection.fromFeatures(segs.filter { it.size >= 2 }.map { seg ->
        Feature.fromGeometry(LineString.fromLngLats(seg.map { Point.fromLngLat(it.longitude, it.latitude) }))
    })
}

private fun stopFeatures(stops: List<Stop>): FeatureCollection =
    FeatureCollection.fromFeatures(stops.filter { it.lat != null && it.lng != null }.mapIndexed { i, s ->
        Feature.fromGeometry(Point.fromLngLat(s.lng!!, s.lat!!)).apply { addStringProperty("n", "${i + 1}") }
    })

private fun fitToTrip(
    map: MapLibreMap, stops: List<Stop>, roadSegments: List<List<com.google.android.gms.maps.model.LatLng>>
) {
    val pts = stops.mapNotNull { s -> s.lat?.let { la -> s.lng?.let { ln -> LatLng(la, ln) } } } +
        roadSegments.flatten().map { LatLng(it.latitude, it.longitude) }
    when {
        pts.isEmpty() -> map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(22.755, 121.15), 11.0))  // 台東市區
        pts.size == 1 -> map.moveCamera(CameraUpdateFactory.newLatLngZoom(pts[0], 15.0))
        else -> map.moveCamera(CameraUpdateFactory.newLatLngBounds(
            LatLngBounds.Builder().includes(pts).build(), 80))
    }
}
