package com.nuvio.tv.ui.screens.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.EpisodeListOrderDataStore
import com.nuvio.tv.domain.model.EpisodeListOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class EpisodeListOrderViewModel @Inject constructor(
    private val store: EpisodeListOrderDataStore,
) : ViewModel() {
    val order = store.order.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        EpisodeListOrder.OldestFirst,
    )

    fun setOrder(order: EpisodeListOrder) {
        viewModelScope.launch { store.set(order) }
    }
}
