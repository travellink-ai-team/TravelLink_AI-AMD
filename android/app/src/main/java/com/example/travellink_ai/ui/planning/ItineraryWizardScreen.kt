package com.example.travellink_ai.ui.planning

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItineraryWizardScreen(viewModel: ItineraryViewModel) {
    val peopleFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(150)
        peopleFocusRequester.requestFocus()
    }
    // 狀態管理：日期與時間
    var startDate by remember { mutableStateOf("") }
    var startTime by remember { mutableStateOf("09:00") }
    var endDate by remember { mutableStateOf("") }
    var endTime by remember { mutableStateOf("18:00") }

    // 狀態管理：人數與節奏
    var people by remember { mutableStateOf("2人") }
    var pace by remember { mutableStateOf("平衡") }

    // 狀態管理：出發車站
    var departureStation by remember { mutableStateOf("台東車站") }

    // 控制 Picker 顯示
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pickingStart by remember { mutableStateOf(true) }

    val datePickerState = rememberDatePickerState()
    val timePickerState = rememberTimePickerState(initialHour = 9, initialMinute = 0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = "開始規劃你的台東微旅行",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // --- 開始時間設定 ---
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("出發設定", style = MaterialTheme.typography.titleMedium)
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(
                        onClick = { pickingStart = true; showDatePicker = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.DateRange, null)
                        Spacer(Modifier.width(4.dp))
                        Text(if (startDate.isEmpty()) "日期" else startDate)
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { pickingStart = true; showTimePicker = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Notifications, null)
                        Spacer(Modifier.width(4.dp))
                        Text(startTime)
                    }
                }
            }
        }

        // --- 結束時間設定 ---
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("回程設定", style = MaterialTheme.typography.titleMedium)
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(
                        onClick = { pickingStart = false; showDatePicker = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.DateRange, null)
                        Spacer(Modifier.width(4.dp))
                        Text(if (endDate.isEmpty()) "日期" else endDate)
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { pickingStart = false; showTimePicker = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Notifications, null)
                        Spacer(Modifier.width(4.dp))
                        Text(endTime)
                    }
                }
            }
        }

        // --- 出發 / 回程車站 ---
        val stationOptions = listOf("台東車站", "知本車站", "鹿野車站", "關山車站", "池上車站")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("出發 / 回程車站", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    stationOptions.forEach { station ->
                        val selected = departureStation == station
                        FilterChip(
                            selected = selected,
                            onClick = { departureStation = station },
                            label = { Text(station) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = departureStation,
                    onValueChange = { departureStation = it },
                    label = { Text("或輸入其他車站") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }

        // 基本輸入
        OutlinedTextField(
            value = people,
            onValueChange = { people = it },
            label = { Text("同行人數") },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(peopleFocusRequester)
        )

        OutlinedTextField(
            value = pace,
            onValueChange = { pace = it },
            label = { Text("旅遊節奏 (輕快/平衡/悠閒)") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                if (startDate.isNotEmpty() && endDate.isNotEmpty()) {
                    viewModel.generateItineraryWithAI(
                        startDateTime = "$startDate $startTime",
                        endDateTime = "$endDate $endTime",
                        people = people,
                        pace = pace,
                        departureStation = departureStation
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = startDate.isNotEmpty() && endDate.isNotEmpty()
        ) {
            Text("AI 生成行程")
        }
    }

    // --- DatePickerDialog ---
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val date = datePickerState.selectedDateMillis?.let {
                        SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(it))
                    } ?: ""
                    if (pickingStart) startDate = date else endDate = date
                    showDatePicker = false
                }) { Text("確定") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // --- TimePickerDialog ---
    if (showTimePicker) {
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val formattedTime = String.format("%02d:%02d", timePickerState.hour, timePickerState.minute)
                    if (pickingStart) startTime = formattedTime else endTime = formattedTime
                    showTimePicker = false
                }) { Text("確定") }
            },
            text = {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                    TimePicker(state = timePickerState)
                }
            }
        )
    }
}