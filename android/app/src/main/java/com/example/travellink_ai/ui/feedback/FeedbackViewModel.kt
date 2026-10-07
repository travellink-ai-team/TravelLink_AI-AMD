package com.example.travellink_ai.ui.feedback

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.travellink_ai.data.local.AppDatabase
import com.example.travellink_ai.data.repository.ItineraryStateHolder
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject

data class StopFeedbackItem(
    val visited: Boolean = true,
    val rating: Int = 0,
    val durationFeedback: String = ""
)

data class FeedbackState(
    val overallRating: Int = 0,
    val wantToVisitAgainStops: List<String> = emptyList(),
    val dontWantToVisitAgainStops: List<String> = emptyList(),
    val highlight: String = "",
    val stopFeedbacks: Map<String, StopFeedbackItem> = emptyMap(),
    val aiScheduleAccurate: Boolean? = null,
    val aiTransitAccurate: Boolean? = null,
    val aiHoursAccurate: Boolean? = null,
    val suggestions: String = ""
)

@HiltViewModel
class FeedbackViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stateHolder: ItineraryStateHolder,
    private val database: AppDatabase,
    private val db: FirebaseFirestore,
    private val firebaseAuth: FirebaseAuth
) : ViewModel() {
    private val tag = "TravelLink_Debug"
    private val itineraryDao = database.itineraryDao()
    private val currentUid: String get() = firebaseAuth.currentUser?.uid ?: ""

    private val _feedbackState = MutableStateFlow(FeedbackState())
    val feedbackState: StateFlow<FeedbackState> = _feedbackState.asStateFlow()

    private val _isFeedbackSubmitting = MutableStateFlow(false)
    val isFeedbackSubmitting: StateFlow<Boolean> = _isFeedbackSubmitting.asStateFlow()

    private val _feedbackSubmitSuccess = MutableStateFlow(false)
    val feedbackSubmitSuccess: StateFlow<Boolean> = _feedbackSubmitSuccess.asStateFlow()

    val itinerary = stateHolder.itinerary

    val pendingFeedbackItems: StateFlow<List<com.example.travellink_ai.data.local.LocalItinerary>> =
        itineraryDao.getAllItinerariesByUser(currentUid)
            .map { list ->
                val now = System.currentTimeMillis()
                list.filter { item ->
                    if (item.feedbackSubmitted) return@filter false
                    val endTime = try {
                        val endPart = item.days.substringAfterLast(" - ").trim()
                        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
                            .parse(endPart)?.time
                    } catch (e: Exception) { null }
                    endTime != null && endTime < now
                }
            }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ── 待回饋通知的「已讀」狀態（通知頁全部已讀用；SharedPreferences 持久化）──
    private val readPrefs = context.getSharedPreferences("feedback_read", Context.MODE_PRIVATE)
    private val _readFeedbackIds = MutableStateFlow(
        (readPrefs.getStringSet("ids", emptySet()) ?: emptySet())
            .mapNotNull { it.toLongOrNull() }.toSet()
    )
    val readFeedbackIds: StateFlow<Set<Long>> = _readFeedbackIds.asStateFlow()

    /** 未讀（尚未標已讀）的待回饋數，供鈴鐺紅點使用。 */
    val unreadPendingFeedbackCount: StateFlow<Int> =
        kotlinx.coroutines.flow.combine(pendingFeedbackItems, _readFeedbackIds) { items, read ->
            items.count { it.id !in read }
        }.stateIn(viewModelScope, SharingStarted.Lazily, 0)

    /** 通知頁「全部已讀」：把目前所有待回饋標為已讀。 */
    fun markAllFeedbackRead() {
        val merged = _readFeedbackIds.value + pendingFeedbackItems.value.map { it.id }.toSet()
        _readFeedbackIds.value = merged
        readPrefs.edit().putStringSet("ids", merged.map { it.toString() }.toSet()).apply()
    }

    fun resetFeedback() {
        _feedbackState.value = FeedbackState()
        _feedbackSubmitSuccess.value = false
    }

    fun updateFeedbackOverallRating(r: Int) { _feedbackState.value = _feedbackState.value.copy(overallRating = r) }
    fun updateFeedbackHighlight(t: String) { _feedbackState.value = _feedbackState.value.copy(highlight = t) }
    fun updateFeedbackSuggestions(t: String) { _feedbackState.value = _feedbackState.value.copy(suggestions = t) }
    fun updateWantToVisitAgainStops(stops: List<String>) { _feedbackState.value = _feedbackState.value.copy(wantToVisitAgainStops = stops) }
    fun updateDontWantToVisitAgainStops(stops: List<String>) { _feedbackState.value = _feedbackState.value.copy(dontWantToVisitAgainStops = stops) }
    fun updateAiScheduleAccurate(v: Boolean?) { _feedbackState.value = _feedbackState.value.copy(aiScheduleAccurate = v) }
    fun updateAiTransitAccurate(v: Boolean?) { _feedbackState.value = _feedbackState.value.copy(aiTransitAccurate = v) }
    fun updateAiHoursAccurate(v: Boolean?) { _feedbackState.value = _feedbackState.value.copy(aiHoursAccurate = v) }

    fun updateStopVisited(stopName: String, visited: Boolean) {
        val current = _feedbackState.value.stopFeedbacks[stopName] ?: StopFeedbackItem()
        _feedbackState.value = _feedbackState.value.copy(
            stopFeedbacks = _feedbackState.value.stopFeedbacks + (stopName to current.copy(visited = visited))
        )
    }

    fun updateStopRating(stopName: String, rating: Int) {
        val current = _feedbackState.value.stopFeedbacks[stopName] ?: StopFeedbackItem()
        _feedbackState.value = _feedbackState.value.copy(
            stopFeedbacks = _feedbackState.value.stopFeedbacks + (stopName to current.copy(rating = rating))
        )
    }

    fun updateStopDuration(stopName: String, duration: String) {
        val current = _feedbackState.value.stopFeedbacks[stopName] ?: StopFeedbackItem()
        _feedbackState.value = _feedbackState.value.copy(
            stopFeedbacks = _feedbackState.value.stopFeedbacks + (stopName to current.copy(durationFeedback = duration))
        )
    }

    fun submitFeedback() {
        val fb = _feedbackState.value
        val itin = stateHolder.itinerary.value ?: run {
            Log.e(tag, "❌ submitFeedback：itinerary 為 null")
            return
        }
        val docId = stateHolder.currentFirestoreDocId.value ?: "my_${System.currentTimeMillis()}"
        viewModelScope.launch(Dispatchers.IO) {
            _isFeedbackSubmitting.value = true
            try {
                val stopMap = fb.stopFeedbacks.map { (name, sf) ->
                    mapOf("name" to name, "visited" to sf.visited,
                          "rating" to sf.rating, "durationFeedback" to sf.durationFeedback)
                }
                val feedbackMap = hashMapOf(
                    "itineraryDocId"             to docId,
                    "itineraryTitle"             to (itin.title.ifBlank { itin.aiTitle }),
                    "region"                     to itin.region,
                    "overallRating"              to fb.overallRating,
                    "wantToVisitAgainStops"      to fb.wantToVisitAgainStops,
                    "dontWantToVisitAgainStops"  to fb.dontWantToVisitAgainStops,
                    "highlight"                  to fb.highlight,
                    "stopFeedbacks"              to stopMap,
                    "aiScheduleAccurate"         to fb.aiScheduleAccurate,
                    "aiTransitAccurate"          to fb.aiTransitAccurate,
                    "aiHoursAccurate"            to fb.aiHoursAccurate,
                    "suggestions"               to fb.suggestions,
                    "submittedAt"               to Timestamp.now()
                )
                db.collection("feedback").add(feedbackMap).await()
                Log.d(tag, "✅ 回饋送出成功：$docId")

                // ── 雙寫網頁端 schema（docs/FEEDBACK_SCHEMA.md 對齊要求）──
                // 位置 micro_trips/{tripId}/feedback/{emailKey}，每位成員一份、重送即覆蓋。
                // 欄位須恰好符合線上規則 isValidFeedback 白名單，多一鍵都會被拒。
                submitWebFormatFeedback(docId, fb, itin)

                val localId = stateHolder.lastGeneratedLocalId
                if (localId >= 0) {
                    itineraryDao.updateFeedbackSubmitted(localId, true)
                    itineraryDao.updateOverallRating(localId, fb.overallRating)
                }

                try {
                    db.collection("micro_trips").document(docId)
                        .set(mapOf("overallRating" to fb.overallRating,
                                   "feedbackSubmittedAt" to Timestamp.now()),
                             SetOptions.merge()).await()
                } catch (e: Exception) {
                    Log.w(tag, "⚠️ test_trips 評分回寫失敗：${e.message}")
                }

                val stopPlaceIds = itin.stops.associate { it.name to it.placeId }
                for ((stopName, sf) in fb.stopFeedbacks) {
                    if (!sf.visited || sf.rating == 0) continue
                    val placeId = stopPlaceIds[stopName]?.takeIf { it.isNotBlank() } ?: continue
                    try {
                        val ref = db.collection("scenic_points").document(placeId)
                        db.runTransaction { tx ->
                            val snap = tx.get(ref)
                            val ratedIds = (snap.get("ratedTripIds") as? List<*>) ?: emptyList<Any>()
                            if (docId in ratedIds) return@runTransaction
                            tx.set(ref, mapOf(
                                "name"        to stopName,
                                "placeId"     to placeId,
                                "ratingSum"   to FieldValue.increment(sf.rating.toDouble()),
                                "ratingCount" to FieldValue.increment(1L),
                                "ratedTripIds" to FieldValue.arrayUnion(docId)
                            ), SetOptions.merge())
                        }.await()
                    } catch (e: Exception) {
                        Log.w(tag, "⚠️ scenic_points 寫入失敗（$stopName）：${e.message}")
                    }
                }
                _feedbackSubmitSuccess.value = true
            } catch (e: Exception) {
                Log.e(tag, "❌ 回饋送出失敗：${e.message}")
            } finally {
                _isFeedbackSubmitting.value = false
            }
        }
    }

    /**
     * 網頁端格式回饋雙寫（FEEDBACK_SCHEMA.md）：
     * tripRating=App 總評分；aiAccuracy=三題準確度答「準」比例映射 1–5；
     * visitedCount 優先取行程進行（W3 tripProgress）的實際打卡數，退回逐站回饋勾選數。
     */
    private suspend fun submitWebFormatFeedback(
        docId: String,
        fb: FeedbackState,
        itin: com.example.travellink_ai.data.model.Itinerary
    ) {
        val email = firebaseAuth.currentUser?.email
        if (email.isNullOrBlank() || docId.isBlank() || docId == "current") return
        try {
            val name = firebaseAuth.currentUser?.displayName?.takeIf { it.isNotBlank() }
                ?: email.substringBefore("@")
            val aiAnswers = listOfNotNull(fb.aiScheduleAccurate, fb.aiTransitAccurate, fb.aiHoursAccurate)
            val aiAccuracy = if (aiAnswers.isEmpty()) 3
                else (1 + aiAnswers.count { it } * 4.0 / aiAnswers.size).toInt()
            val totalStops = itin.stops.count { !it.isStation }
            val visitedCount = countTripCheckIns(docId).takeIf { it > 0 }
                ?: fb.stopFeedbacks.count { it.value.visited }
            val comment = listOf(fb.highlight, fb.suggestions)
                .filter { it.isNotBlank() }.joinToString("／").take(500)
            val entry = hashMapOf<String, Any>(
                "email"        to email,
                "name"         to name.take(100),
                "tripRating"   to fb.overallRating.coerceIn(1, 5),
                "aiAccuracy"   to aiAccuracy.coerceIn(1, 5),
                "visitedCount" to visitedCount,
                "totalStops"   to totalStops,
                "submittedAt"  to System.currentTimeMillis(),
                "appPlatform"  to "android"
            )
            if (comment.isNotBlank()) entry["comment"] = comment
            val emailKey = com.example.travellink_ai.data.model.sanitizeEmailKey(email)
            db.collection("micro_trips").document(docId)
                .collection("feedback").document(emailKey)
                .set(entry).await()
            Log.d(tag, "✅ 網頁格式回饋已雙寫：micro_trips/$docId/feedback/$emailKey")
        } catch (e: Exception) {
            Log.w(tag, "⚠️ 網頁格式回饋雙寫失敗（非致命）：${e.message}")
        }
    }

    /** 從 W3 行程進行的本地快取（SharedPreferences trip_progress）數打卡站數 */
    private fun countTripCheckIns(docId: String): Int = try {
        val raw = context.getSharedPreferences("trip_progress", Context.MODE_PRIVATE)
            .getString(docId, null)
        if (raw == null) 0
        else org.json.JSONObject(raw).optJSONObject("checkIns")?.length() ?: 0
    } catch (e: Exception) { 0 }
}
