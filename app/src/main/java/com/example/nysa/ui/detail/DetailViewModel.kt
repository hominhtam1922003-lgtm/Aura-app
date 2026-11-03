package com.example.nysa.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nysa.NySaApp
import com.example.nysa.data.ProbeTarget
import com.example.nysa.data.ServerProbe
import com.example.nysa.data.ServerStatus
import com.example.nysa.data.local.UserStateEntity
import com.example.nysa.data.model.ServerGroup
import com.example.nysa.data.model.TitleDetail
import com.example.nysa.data.model.TitleItem
import com.example.nysa.data.source.CobePlay
import com.example.nysa.data.source.CobeSource
import com.example.nysa.data.toEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DetailUiState(
    val loading: Boolean = true,
    val detail: TitleDetail? = null,
    val error: String = "",
    val userState: UserStateEntity? = null,
    val serverStatuses: Map<Int, ServerStatus> = emptyMap(),
    val selectedServer: Int = 0,
    val related: List<TitleItem> = emptyList()
)

class DetailViewModel(
    private val app: NySaApp,
    private val item: TitleItem
) : ViewModel() {

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            app.database.userStateDao().observe(item.id).collect { st ->
                _state.update { it.copy(userState = st) }
            }
        }
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = "") }
            try {
                val detail = app.cobe.detail(item.slug)
                app.database.titleDao().upsert(detail.item.toEntity())
                _state.update { it.copy(loading = false, detail = detail) }
                probeServers(detail)
                loadRelated(detail)
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = "Không tải được thông tin phim — nguồn chưa phản hồi. Bấm để thử lại."
                    )
                }
            }
        }
    }

    /**
     * Measured per-server health for the source picker (honesty law:
     * a dead server is shown dead, never silently dropped). Reuses the
     * player's probe: locator episodes resolve first, direct streams
     * are timed, embeds are reported as embeds.
     */
    private fun probeServers(detail: TitleDetail) {
        val firstEp = detail.servers.maxByOrNull { it.episodes.size }
            ?.episodes?.firstOrNull() ?: return
        viewModelScope.launch {
            val statuses = ServerProbe.probeAll(detail.servers, firstEp.number) { ep ->
                when {
                    CobeSource.isLocator(ep.slug) -> try {
                        when (val target = app.cobe.resolvePlay(ep.slug)) {
                            is CobePlay.Hls -> ProbeTarget.Stream(target.url)
                            is CobePlay.Embed -> ProbeTarget.Embed(target.url)
                        }
                    } catch (e: Exception) {
                        ProbeTarget.None
                    }
                    ep.embedUrl.isNotBlank() -> ProbeTarget.Embed(ep.embedUrl)
                    else -> ProbeTarget.None
                }
            }
            _state.update { it.copy(serverStatuses = statuses) }
        }
    }

    private fun loadRelated(detail: TitleDetail) {
        val genreId = detail.genreLinks.firstOrNull()?.second?.toIntOrNull() ?: return
        viewModelScope.launch {
            val page = runCatching {
                if (detail.item.family == com.example.nysa.data.model.Family.ANIME) {
                    app.cobe.animePage(genreId = genreId, page = 1, limit = 12)
                } else {
                    app.cobe.phimPage(genreId = genreId, page = 1, limit = 12)
                }
            }.getOrNull() ?: return@launch
            _state.update {
                it.copy(related = page.items.filter { r -> r.slug != detail.item.slug })
            }
        }
    }

    fun selectServer(index: Int) {
        _state.update { it.copy(selectedServer = index) }
    }

    fun server(): ServerGroup? =
        _state.value.detail?.servers?.getOrNull(_state.value.selectedServer)

    private fun mutateState(transform: (UserStateEntity) -> UserStateEntity) {
        viewModelScope.launch {
            val dao = app.database.userStateDao()
            val current = dao.get(item.id) ?: UserStateEntity(titleId = item.id)
            dao.upsert(transform(current))
        }
    }

    fun toggleFavorite() = mutateState { it.copy(favorite = !it.favorite) }
    fun toggleWatchLater() = mutateState { it.copy(watchLater = !it.watchLater) }
    fun toggleNotify() = mutateState { st ->
        // Turning notifications on also tracks the title (the user's
        // definition: favorite OR notify OR started watching). The
        // new-episode baseline is set by the worker on first check.
        st.copy(notifyOn = !st.notifyOn, following = st.following || !st.notifyOn)
    }
}
