package com.example.nysa.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nysa.NySaApp
import com.example.nysa.data.toEntity
import com.example.nysa.data.model.TitleItem
import com.example.nysa.ui.components.GlassChip
import com.example.nysa.ui.components.NoteText
import com.example.nysa.ui.components.TitlePosterCard
import com.example.nysa.ui.theme.NySaTextMuted
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val filter: String = "all",           // all | series | movie | anime
    val results: List<TitleItem> = emptyList(),
    val loading: Boolean = false,
    val searched: Boolean = false
)

class SearchViewModel(private val app: NySaApp) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private var generation = 0

    fun setQuery(q: String) {
        _state.update { it.copy(query = q) }
    }

    fun setFilter(f: String) {
        _state.update { it.copy(filter = f) }
        searchNow()
    }

    @OptIn(FlowPreview::class)
    fun bindQueryFlow(source: kotlinx.coroutines.flow.Flow<String>) {
        viewModelScope.launch {
            source.debounce(350).distinctUntilChanged().collect { searchNow() }
        }
    }

    fun searchNow() {
        val q = _state.value.query.trim()
        val gen = ++generation
        if (q.isBlank()) {
            _state.update { it.copy(results = emptyList(), loading = false, searched = false) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val page = runCatching { app.cobe.searchKeyword(q, page = 1, limit = 36) }.getOrNull()
            if (gen != generation) return@launch
            val all = page?.items ?: emptyList()
            app.database.titleDao().upsertAll(all.map { it.toEntity() })
            // "Full" is the movie marker the source mapping sets;
            // series carry "Tập N". Family always wins over the marker.
            val filtered = when (_state.value.filter) {
                "series" -> all.filter {
                    it.family == com.example.nysa.data.model.Family.PHIM &&
                        it.latestLabel != "Full"
                }
                "movie" -> all.filter {
                    it.family == com.example.nysa.data.model.Family.PHIM &&
                        it.latestLabel == "Full"
                }
                "anime" -> all.filter { it.family == com.example.nysa.data.model.Family.ANIME }
                else -> all
            }
            _state.update {
                it.copy(results = filtered, loading = false, searched = true)
            }
            app.settings.addSearchQuery(q)
        }
    }
}

/**
 * Tìm kiếm: results filter as you type (the approved design); the
 * filter chips split Phim bộ / Phim lẻ / Anime from the same result
 * pool — family is a property of each record, never mixed up.
 */
@Composable
fun SearchScreen(
    onOpenTitle: (TitleItem) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as NySaApp
    val vm = remember { SearchViewModel(app) }
    val state by vm.state.collectAsState()
    val history by app.settings.searchHistory.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }

    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) {
        vm.bindQueryFlow(snapshotFlow { text })
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = text,
                onValueChange = {
                    text = it
                    vm.setQuery(it)
                },
                placeholder = { Text("Tìm phim, anime…", color = NySaTextMuted) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.White.copy(alpha = 0.08f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.08f),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Color.White
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.height(0.dp))
            Text(
                "Huỷ",
                color = Color.White,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 12.dp)
                    .clickable(onClick = onClose)
                    .padding(4.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                "all" to "Tất cả", "series" to "Phim bộ",
                "movie" to "Phim lẻ", "anime" to "Anime"
            ).forEach { (key, label) ->
                GlassChip(
                    label = label,
                    selected = state.filter == key,
                    onClick = { vm.setFilter(key) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        if (text.isBlank() && history.isNotEmpty()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("Đã tìm gần đây", color = NySaTextMuted, fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                history.take(8).forEach { h ->
                    Text(
                        h,
                        color = Color.White,
                        fontSize = 14.5.sp,
                        modifier = Modifier
                            .clickable {
                                text = h
                                vm.setQuery(h)
                                vm.searchNow()
                            }
                            .padding(vertical = 7.dp)
                    )
                }
                Text(
                    "Xoá lịch sử tìm kiếm",
                    color = NySaTextMuted,
                    fontSize = 12.5.sp,
                    modifier = Modifier
                        .clickable {
                            scope.launch { app.settings.clearSearchHistory() }
                        }
                        .padding(vertical = 8.dp)
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.loading -> CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
                state.searched && state.results.isEmpty() -> NoteText(
                    "Không thấy kết quả cho “${state.query}”.",
                    Modifier.align(Alignment.TopCenter).padding(16.dp)
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, bottom = 110.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.results, key = { it.id }) { item ->
                        TitlePosterCard(
                            item = item,
                            onClick = { onOpenTitle(item) },
                            cardWidth = 104.dp
                        )
                    }
                }
            }
        }
    }
}
