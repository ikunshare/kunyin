package com.ikunshare.music.ui.screens.discover

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ikunshare.music.ui.KunyinViewModelFactory
import com.ikunshare.music.ui.screens.charts.ChartsScreen
import com.ikunshare.music.ui.screens.player.PlayerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverTabScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModelFactory: KunyinViewModelFactory
) {
    // Survives process death so the pager doesn't snap back to 发现歌单.
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    // Owns the insets and padding; each page drops it straight into its topBar slot.
    val header: @Composable () -> Unit = {
        SingleChoiceSegmentedButtonRow(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            SegmentedButton(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
            ) { Text("发现歌单") }
            SegmentedButton(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
            ) { Text("排行榜") }
        }
    }

    AnimatedContent(targetState = selectedTab, label = "discover_tab") { tab ->
        when (tab) {
            0 -> DiscoverScreen(
                nav = navController,
                factory = viewModelFactory,
                header = header
            )
            else -> ChartsScreen(
                player = playerViewModel,
                factory = viewModelFactory,
                header = header
            )
        }
    }
}
