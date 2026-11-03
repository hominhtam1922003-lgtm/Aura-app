package com.example.nysa.ui.home

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nysa.NySaApp
import com.example.nysa.R
import com.example.nysa.data.local.UserStateEntity
import com.example.nysa.data.model.Family
import com.example.nysa.data.model.TitleItem
import com.example.nysa.data.toItem
import com.example.nysa.ui.components.ContinueCard
import com.example.nysa.ui.components.GlassChip
import com.example.nysa.ui.components.NoteText
import com.example.nysa.ui.components.ShelfHeader
import com.example.nysa.ui.components.TitlePosterCard
import com.example.nysa.ui.components.glassSurface

/**
 * Trang chủ: family chips (Phim/Anime are separate tabs — the chips
 * jump there), "Đang xem dở" from the local store, then the source's
 * own homepage shelves. All state lives in the ViewModel + Room, so
 * leaving and returning to this tab never reloads (verdict 1.3.14).
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenTitle: (TitleItem) -> Unit,
    onFamilyChip: (Family) -> Unit,
    onResume: (TitleItem) -> Unit,
    onGoSearch: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as NySaApp
    val state by viewModel.state.collectAsState()

    val continueStates by app.database.userStateDao().continueWatching()
        .collectAsState(initial = emptyList())
    val continueTitles = rememberTitles(app, continueStates)

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 110.dp)
        ) {
            item(key = "home-header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "NySa",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .glassSurface(CircleShape, com.example.nysa.ui.theme.LocalGlassEnabled.current)
                            .clickable(onClick = onGoSearch),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_search),
                            contentDescription = "Tìm kiếm",
                            tint = Color.White,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GlassChip(label = "Phim", selected = false,
                        onClick = { onFamilyChip(Family.PHIM) })
                    GlassChip(label = "Anime", selected = false,
                        onClick = { onFamilyChip(Family.ANIME) })
                }
                Spacer(Modifier.height(8.dp))
            }

            if (continueTitles.isNotEmpty()) {
                item(key = "continue") {
                    Spacer(Modifier.height(10.dp))
                    ShelfHeader("Đang xem dở")
                    Spacer(Modifier.height(10.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(continueTitles, key = { it.second.id }) { (st, item) ->
                            ContinueCard(
                                item = item,
                                progressFraction = st.progressFraction,
                                progressLabel = st.lastUnitLabel,
                                onClick = { onResume(item) }
                            )
                        }
                    }
                }
            }

            if (state.loading && state.shelves.isEmpty()) {
                item(key = "loading") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
            }
            if (state.error.isNotBlank() && state.shelves.isEmpty()) {
                item(key = "error") {
                    NoteText(state.error, Modifier.padding(16.dp))
                }
            }
            items(state.shelves, key = { it.name }) { shelf ->
                Spacer(Modifier.height(18.dp))
                ShelfHeader(shelf.name)
                Spacer(Modifier.height(10.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(shelf.items, key = { it.id + it.slug }) { item ->
                        TitlePosterCard(item = item, onClick = { onOpenTitle(item) })
                    }
                }
            }
        }
    }
}

/** Join user-state rows to their cached titles (instant, local-only). */
@Composable
fun rememberTitles(
    app: NySaApp,
    states: List<UserStateEntity>
): List<Pair<UserStateEntity, TitleItem>> {
    val ids = states.map { it.titleId }
    val entitiesFlow = if (ids.isEmpty()) {
        kotlinx.coroutines.flow.flowOf(emptyList())
    } else {
        app.database.titleDao().observeByIds(ids)
    }
    val entities by entitiesFlow.collectAsState(initial = emptyList())
    val byId = entities.associateBy { it.id }
    return states.mapNotNull { st -> byId[st.titleId]?.let { st to it.toItem() } }
}
