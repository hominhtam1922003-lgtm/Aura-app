package com.example.nysa.ui.me

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nysa.NySaApp
import com.example.nysa.data.AppSettings
import com.example.nysa.data.ReportStore
import com.example.nysa.data.SettingsStore
import com.example.nysa.ui.theme.NySaGreen
import com.example.nysa.ui.theme.NySaRed
import com.example.nysa.ui.theme.NySaTextMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tab Tôi: the Liquid Glass master switch (off = the plain black
 * theme everywhere, per the approved design), the player settings the
 * old app carried, and the source-health card with MEASURED status.
 */
@Composable
fun MeScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as NySaApp
    val settings by app.settings.flow.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()

    var healthOk by remember { mutableStateOf<Boolean?>(null) }
    var healthMs by remember { mutableStateOf(-1L) }
    var healthAt by remember { mutableStateOf("") }
    var reportCount by remember { mutableStateOf(0) }

    fun checkHealth() {
        scope.launch {
            val (ok, ms) = app.cobe.health()
            healthOk = ok
            healthMs = ms
            healthAt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                .format(java.util.Date())
        }
    }
    LaunchedEffect(Unit) {
        checkHealth()
        reportCount = withContext(Dispatchers.IO) { ReportStore.load(context).size }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(52.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Text("N", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.size(12.dp))
            Column {
                Text("NySa", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
                Text("App xem phim cá nhân", color = NySaTextMuted, fontSize = 12.5.sp)
            }
        }
        Spacer(Modifier.height(22.dp))

        SectionTitle("GIAO DIỆN")
        SettingSwitchRow(
            label = "Liquid Glass",
            sub = if (settings.liquidGlass) {
                "Kính trong suốt kiểu iPhone — đang bật"
            } else {
                "Đã tắt — giao diện nền đen thường"
            },
            checked = settings.liquidGlass,
            onChecked = { v -> scope.launch { app.settings.set(SettingsStore.LIQUID_GLASS, v) } }
        )
        Spacer(Modifier.height(18.dp))

        SectionTitle("TRÌNH PHÁT")
        SettingChoiceRow(
            label = "Chất lượng mặc định",
            options = listOf(
                "CAO_NHAT" to "Cao nhất", "P1080" to "1080p", "P720" to "720p",
                "P480" to "480p", "AUTO" to "Tự động"
            ),
            current = settings.qualityDefault,
            onPick = { scope.launch { app.settings.setString(SettingsStore.QUALITY, it) } }
        )
        SettingChoiceRow(
            label = "Ưu tiên bản dịch",
            options = listOf("TM" to "Thuyết minh", "VS" to "Vietsub", "LT" to "Lồng tiếng"),
            current = settings.versionPriority,
            onPick = { scope.launch { app.settings.setString(SettingsStore.VERSION_PRIORITY, it) } }
        )
        SettingChoiceRow(
            label = "Chế độ hiển thị",
            options = listOf(
                "FIT" to "Vừa khung", "FULL" to "Lấp đầy",
                "FULLSUB" to "Lấp đầy (giữ phụ đề)", "STRETCH" to "Kéo giãn"
            ),
            current = settings.playerResize,
            onPick = { scope.launch { app.settings.setString(SettingsStore.PLAYER_RESIZE, it) } }
        )
        SettingSwitchRow(
            label = "Tự chọn server sống nhanh nhất",
            sub = "Đo thật các server rồi ưu tiên đường đang tốt",
            checked = settings.autoFastestServer,
            onChecked = { v -> scope.launch { app.settings.set(SettingsStore.AUTO_SERVER, v) } }
        )
        SettingSwitchRow(
            label = "Tự phát tập tiếp theo",
            sub = "Hết tập tự chuyển tập kế",
            checked = settings.autoNextEpisode,
            onChecked = { v -> scope.launch { app.settings.set(SettingsStore.AUTO_NEXT, v) } }
        )
        SettingSwitchRow(
            label = "Bỏ qua mở đầu",
            sub = "Nút bỏ qua học theo từng bộ từ cách bạn tua",
            checked = settings.skipIntroOutro,
            onChecked = { v -> scope.launch { app.settings.set(SettingsStore.SKIP_INTRO, v) } }
        )
        SettingSwitchRow(
            label = "PiP — xem cửa sổ nhỏ",
            sub = "Bấm Home khi đang phát sẽ thu nhỏ thành cửa sổ nổi",
            checked = settings.pipEnabled,
            onChecked = { v -> scope.launch { app.settings.set(SettingsStore.PIP, v) } }
        )
        SettingSwitchRow(
            label = "Chặn quảng cáo trong trang phát web",
            sub = "Chặn các domain quảng cáo/cờ bạc đã biết trong embed",
            checked = settings.adBlock,
            onChecked = { v -> scope.launch { app.settings.set(SettingsStore.AD_BLOCK, v) } }
        )
        Spacer(Modifier.height(18.dp))

        SectionTitle("THÔNG BÁO TẬP MỚI")
        SettingSwitchRow(
            label = "Báo khi có tập mới",
            sub = "Chỉ tính tập nguồn thêm SAU khi bạn theo dõi/yêu thích/bắt đầu xem",
            checked = settings.notifyNewEpisode,
            onChecked = { v -> scope.launch { app.settings.set(SettingsStore.NOTIFY_NEW, v) } }
        )
        SettingChoiceRow(
            label = "Tần suất kiểm tra",
            options = listOf(
                "CONTINUOUS" to "Liên tục (15 phút)", "HOURLY" to "Mỗi giờ",
                "SIX_HOURS" to "Mỗi 6 giờ", "ON_OPEN" to "Chỉ khi mở app"
            ),
            current = settings.checkInterval,
            onPick = { scope.launch { app.settings.setString(SettingsStore.CHECK_INTERVAL, it) } }
        )
        Spacer(Modifier.height(18.dp))

        SectionTitle("SỨC KHOẺ NGUỒN")
        Box(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .padding(14.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(9.dp).clip(CircleShape).background(
                            when (healthOk) {
                                true -> NySaGreen
                                false -> NySaRed
                                null -> NySaTextMuted
                            }
                        )
                    )
                    Spacer(Modifier.size(9.dp))
                    Text(
                        when (healthOk) {
                            true -> "cobephim.fun — đang sống" +
                                (if (healthMs >= 0) " · ${healthMs} ms" else "")
                            false -> "cobephim.fun — không phản hồi"
                            null -> "cobephim.fun — đang kiểm tra…"
                        },
                        color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "API: ${app.cobe.currentApiBase()}" +
                        (if (healthAt.isNotBlank()) " · đo lúc $healthAt" else ""),
                    color = NySaTextMuted, fontSize = 11.5.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Kiểm tra lại",
                    color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { checkHealth() }.padding(2.dp)
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Báo lỗi đã ghi: $reportCount", color = NySaTextMuted, fontSize = 12.5.sp)
            if (reportCount > 0) {
                Text(
                    "Xoá",
                    color = NySaTextMuted, fontSize = 12.5.sp,
                    modifier = Modifier.clickable {
                        scope.launch {
                            withContext(Dispatchers.IO) { ReportStore.clear(context) }
                            reportCount = 0
                        }
                    }.padding(4.dp)
                )
            }
        }
        Spacer(Modifier.height(120.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = Color(0xFFFF9E2C),
        fontSize = 12.5.sp,
        fontWeight = FontWeight.ExtraBold,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun SettingSwitchRow(
    label: String,
    sub: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
            if (sub.isNotBlank()) {
                Text(sub, color = NySaTextMuted, fontSize = 12.sp)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = Color.White,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color.White.copy(alpha = 0.25f)
            )
        )
    }
}

@Composable
private fun SettingChoiceRow(
    label: String,
    options: List<Pair<String, String>>,
    current: String,
    onPick: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = Color.White, fontSize = 14.5.sp,
                fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(
                options.firstOrNull { it.first == current }?.second ?: current,
                color = NySaTextMuted, fontSize = 13.sp
            )
        }
        if (open) {
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { (value, text) ->
                    val selected = value == current
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (selected) Color.White.copy(alpha = 0.16f)
                                else Color.White.copy(alpha = 0.05f)
                            )
                            .clickable {
                                onPick(value)
                                open = false
                            }
                            .padding(horizontal = 12.dp, vertical = 9.dp)
                    ) {
                        Text(
                            text,
                            color = if (selected) Color.White else NySaTextMuted,
                            fontSize = 13.5.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}
