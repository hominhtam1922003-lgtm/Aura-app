package com.example.nysa.ui.section

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nysa.data.model.Family
import com.example.nysa.data.model.STATUS_ALL
import com.example.nysa.data.model.STATUS_COMPLETED
import com.example.nysa.data.model.STATUS_ONGOING
import com.example.nysa.data.model.TitleItem
import com.example.nysa.ui.components.GlassChip
import com.example.nysa.ui.components.NoteText
import com.example.nysa.ui.components.TitlePosterCard
import com.example.nysa.ui.theme.NySaGenreOrange
import com.example.nysa.ui.theme.NySaSurface
import com.example.nysa.ui.theme.NySaTextMuted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The Phim / Anime tab. Sub-type chips on top, a filter sheet whose
 * groups contain ONLY this section's own values, and a 3-column grid
 * that loads the next page by itself near the end (the "Tải thêm"
 * button is gone — verdict 1.3.14).
 */
@Composable
fun SectionScreen(
    viewModel: SectionViewModel,
    onOpenTitle: (TitleItem) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val gridState = rememberLazyGridState()
    var filterOpen by remember { mutableStateOf(false) }

    // Infinite scroll: when the last visible item nears the end of
    // what is loaded, fetch the next page (VM guards duplicates).
    LaunchedEffect(gridState, state.items.size) {
        snapshotFlow { gridState.layoutInfo }
            .map { info ->
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                last to info.totalItemsCount
            }
            .distinctUntilChanged()
            .collect { (last, total) ->
                if (total > 0 && last >= total - 6) viewModel.loadMore()
            }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                viewModel.family.label,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black
            )
            Spacer(Modifier.weight(1f))
            GlassChip(label = "Bộ lọc", selected = filterOpen, onClick = { filterOpen = true })
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val subs = listOf("all" to "Tất cả", "series" to "Phim bộ", "movie" to "Phim lẻ")
            subs.forEach { (key, label) ->
                GlassChip(
                    label = label,
                    selected = state.subType == key,
                    onClick = { viewModel.setSubType(key) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.loading && state.items.isEmpty() -> {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                state.error.isNotBlank() && state.items.isEmpty() -> {
                    NoteText(
                        state.error,
                        Modifier.align(Alignment.TopCenter).padding(16.dp)
                            .clickable { viewModel.reload() }
                    )
                }
                state.items.isEmpty() -> {
                    NoteText(
                        "Chưa có nội dung khớp bộ lọc này.",
                        Modifier.align(Alignment.TopCenter).padding(16.dp)
                    )
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        state = gridState,
                        contentPadding = PaddingValues(
                            start = 16.dp, end = 16.dp, bottom = 110.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(state.items, key = { it.id }) { item ->
                            TitlePosterCard(
                                item = item,
                                onClick = { onOpenTitle(item) },
                                cardWidth = 104.dp
                            )
                        }
                        if (state.loadingMore) {
                            item(span = { GridItemSpan(3) }, key = "more") {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        color = Color.White,
                                        modifier = Modifier.height(22.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (filterOpen) {
        SectionFilterSheet(
            viewModel = viewModel,
            onDismiss = { filterOpen = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionFilterSheet(
    viewModel: SectionViewModel,
    onDismiss: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    var genreId by remember { mutableIntStateOf(state.genreId) }
    var countryId by remember { mutableIntStateOf(state.countryId) }
    var year by remember { mutableIntStateOf(state.year) }
    var status by remember { mutableIntStateOf(state.status) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = NySaSurface
    ) {
        // Scrollable (1.0.6 fix): the old fixed Column was taller than
        // the screen with the Phim genre group alone (~80 chips), so
        // Quốc gia → Năm → Trạng thái → the action row were clipped
        // below the visible area with no way to reach them.
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                .padding(bottom = 28.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "Bộ lọc — ${viewModel.family.label}",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(14.dp))

            FilterGroup("THỂ LOẠI") {
                FilterChipRow(
                    options = listOf(0 to "Tất cả") +
                        state.genreOptions.map { (it.id.toIntOrNull() ?: 0) to it.name },
                    selected = genreId,
                    onSelect = { genreId = it }
                )
            }
            if (viewModel.family == Family.PHIM) {
                FilterGroup("QUỐC GIA") {
                    FilterChipRow(
                        options = listOf(0 to "Tất cả") +
                            state.countryOptions.map { (it.id.toIntOrNull() ?: 0) to it.name },
                        selected = countryId,
                        onSelect = { countryId = it }
                    )
                }
            }
            if (state.yearOptions.isNotEmpty()) {
                FilterGroup("NĂM") {
                    FilterChipRow(
                        options = listOf(0 to "Tất cả") +
                            state.yearOptions.map { it to it.toString() },
                        selected = year,
                        onSelect = { year = it }
                    )
                }
            }
            FilterGroup("TRẠNG THÁI") {
                FilterChipRow(
                    options = listOf(
                        STATUS_ALL to "Tất cả",
                        STATUS_ONGOING to "Đang ra tiếp",
                        STATUS_COMPLETED to "Trọn bộ"
                    ),
                    selected = status,
                    onSelect = { status = it }
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassChip(label = "Xoá lọc", selected = false, onClick = {
                    genreId = 0; countryId = 0; year = 0; status = STATUS_ALL
                })
                GlassChip(label = "Lọc", selected = true, onClick = {
                    viewModel.applyFilters(genreId, countryId, year, status)
                    onDismiss()
                })
            }
        }
    }
}

@Composable
private fun FilterGroup(title: String, content: @Composable () -> Unit) {
    // Genre-law: group labels are BRIGHT ORANGE #FF9E2C, bold.
    Text(
        title,
        color = NySaGenreOrange,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.ExtraBold,
        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
    )
    content()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterChipRow(
    options: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    // Real FlowRow (1.0.3 fix): the old hand-chunked rows of 4 forced
    // long labels into whatever space a plain Row had left, squeezing
    // them into two-line circles and per-character vertical pills on
    // the device ("Hành Động", "Tâm Lý", "Thần Thoại"). FlowRow
    // measures every chip at its intrinsic width and wraps whole
    // chips to the next line instead.
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, label) ->
            GlassChip(
                label = label,
                selected = selected == value,
                onClick = { onSelect(value) }
            )
        }
    }
    if (options.isEmpty()) {
        Text("Chưa có dữ liệu", color = NySaTextMuted, fontSize = 12.sp)
    }
}
