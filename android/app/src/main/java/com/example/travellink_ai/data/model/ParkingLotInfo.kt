package com.example.travellink_ai.data.model

import com.google.android.gms.maps.model.LatLng

data class ParkingLotInfo(
    val id: String,
    val name: String,
    val position: LatLng,
    val totalSpaces: Int,                // 汽車（小型車）車位；0＝不知道
    val motoSpots: Int = 0,              // 機車車位；0＝不知道
    val source: String = "tdx",          // tdx／taitung_gov_parking（網頁 parking-data.js）
    val walkMins: Int? = null,           // 停好走到景點要幾分鐘（選定時才有）
    val walkEstimated: Boolean = false   // true＝Directions 沒回答，用直線距離推估
)
