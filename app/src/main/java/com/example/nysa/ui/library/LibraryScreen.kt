package com.example.nysa.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nysa.NySaApp
import com.example.nysa.data.TimeText
import com.example.nysa.data.model.TitleItem
import com.example.nysa.ui.components.ContinueCard
import com.example.nysa.ui.components.NoteText
import com.example.nysa.ui.components.ShelfHeader
import com.example.nysa.ui.components.TitlePosterCard
import com.example.nysa.ui.home.rememberTitles

/**
 * Kho của tôi: everything the user keeps — đang xem dở, yêu thích,
 * đang theo dõi (with the newest episode + relative time under each
 * title, never a "+N"), xem sau, lịch sử. All local (Room), so this
 * tab renders instantly and keeps its state across tab switches.
 */
@Composable
fun LibraryScreen(
    onOpenTitle: (TitleItem) -> Unit,
    onResume: (TitleItem) -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as NySaApp
    val dao = app.database.userStateDao()

    val continueStates by dao.continueWatching().collectAsState(initial = emptyList())
    val favoriteStates by dao.favorites().collectAsState(initial = emptyList())
    val trackedStates by dao.tracked().collectAsState(initial = emptyList())
    val laterStates by dao.watchLater().collectAsState(initial = emptyList())
    val historyStates by dao.history(30).collectAsState(initial = emptyList())

    val continueTitles = rememberTitles(app, continueStates)
    val favoriteTitles = rememberTitles(app, favoriteStates)
    val trackedTitles = rememberTitles(app, trackedStates)
    val laterTitles = rememberTitles(app, laterStates)
    val historyTitles = rememberTitles(app, historyStates)

    val isEmpty = continueTitles.isEmpty() && favoriteTitles.isEmpty() &&
        trackedTitles.isEmpty() && laterTitles.isEmpty() && historyTitles.isEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 110.dp)
    ) {
        item(key = "lib-header") {
            Text(
                "Kho của tôi",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(start = 16.dp, top = 10.dp)
            )
            Spacer(Modifier.height(6.dp))
        }
        if (isEmpty) {
            item(key = "lib-empty") {
                NoteText(
                    "Chưa có gì trong kho — xem một phim hoặc bấm Yêu thích ở trang chi tiết, mọi thứ sẽ nằm ở đây.",
                    Modifier.padding(16.dp)
                )
            }
        }
        if (continueTitles.isNotEmpty()) {
            item(key = "lib-continue") {
                Spacer(Modifier.height(12.dp))
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
        if (trackedTitles.isNotEmpty()) {
            item(key = "lib-tracked") {
                Spacer(Modifier.height(18.dp))
                ShelfHeader("Đang theo dõi")
                Spacer(Modifier.height(10.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(trackedTitles, key = { it.second.id }) { (st, item) ->
                        TitlePosterCard(
                            item = item.copy(
                                latestLabel = if (st.latestCount > 0) {
                                    "Tập ${st.latestCount}"
                                } else item.latestLabel,
                                updatedEpochSec = st.lastTouchedEpochSec
                            ),
                            onClick = { onOpenTitle(item) }
                        )
                    }
                }
            }
        }
        if (favoriteTitles.isNotEmpty()) {
            item(key = "lib-fav") {
                Spacer(Modifier.height(18.dp))
                ShelfHeader("Yêu thích")
                Spacer(Modifier.height(10.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(favoriteTitles, key = { it.second.id }) { (_, item) ->
                        TitlePosterCard(item = item, onClick = { onOpenTitle(item) })
                    }
                }
            }
        }
        if (laterTitles.isNotEmpty()) {
            item(key = "lib-later") {
                Spacer(Modifier.height(18.dp))
                ShelfHeader("Xem sau")
                Spacer(Modifier.height(10.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(laterTitles, key = { it.second.id }) { (_, item) ->
                        TitlePosterCard(item = item, onClick = { onOpenTitle(item) })
                    }
                }
            }
        }
        if (historyTitles.isNotEmpty()) {
            item(key = "lib-history") {
                Spacer(Modifier.height(18.dp))
                ShelfHeader("Đã xem gần đây")
                Spacer(Modifier.height(10.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(historyTitles, key = { it.second.id }) { (st, item) ->
                        TitlePosterCard(
                            item = item.copy(
                                latestLabel = st.lastUnitLabel.ifBlank { item.latestLabel },
                                updatedEpochSec = st.lastTouchedEpochSec
                            ),
                            onClick = { onOpenTitle(item) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Lần chạm gần nhất: ${
                        historyTitles.firstOrNull()?.first?.lastTouchedEpochSec
                            ?.takeIf { it > 0 }?.let { TimeText.relative(it) } ?: "—"
                    }",
                    color = Color(0xFF9A9AA3),
                    fontSize = 11.5.sp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }
}
