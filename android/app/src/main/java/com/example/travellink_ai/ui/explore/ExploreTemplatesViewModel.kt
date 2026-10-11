package com.example.travellink_ai.ui.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.travellink_ai.data.model.ExploreTemplates
import com.example.travellink_ai.data.repository.ExploreTemplatesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ExploreTemplatesViewModel @Inject constructor(
    private val repo: ExploreTemplatesRepository
) : ViewModel() {
    private val _templates = MutableStateFlow<ExploreTemplates?>(null)
    val templates: StateFlow<ExploreTemplates?> = _templates.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        viewModelScope.launch {
            _templates.value = repo.load()
            _loading.value = false
        }
    }
}
