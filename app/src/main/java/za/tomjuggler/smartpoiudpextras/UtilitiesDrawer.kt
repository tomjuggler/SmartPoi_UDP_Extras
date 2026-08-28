package za.tomjuggler.smartpoiudpextras

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.ui.geometry.Offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Utilities menu — slide-in drawer listing all features.
 * Drawer state is driven directly (no mirrored boolean), so the hamburger,
 * swipe gestures and item taps all stay in sync.
 */
@Composable
fun UtilitiesDrawer(
    current: Screen,
    onSelect: (Screen) -> Unit,
    content: @Composable () -> Unit
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Color(0xFF10141F)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(90.dp)
                        .background(Brush.linearGradient(listOf(NeonCyan, NeonMagenta, NeonYellow)))
                ) {
                    Text(
                        "SmartPoi Utilities",
                        fontSize = 24.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                        color = Color.Black,
                        modifier = Modifier.align(Alignment.CenterStart).padding(16.dp)
                    )
                }
                listOf(
                    Screen.Home,
                    Screen.ComputerGenerated,
                    Screen.ZapGame,
                    Screen.AudioReactive,
                    Screen.LightSaber,
                    Screen.MagicPoiParties,
                    Screen.Settings
                ).forEach { s ->
                    NavigationDrawerItem(
                        icon = { Icon(s.icon, contentDescription = null) },
                        label = { Text(s.title, fontSize = 18.sp) },
                        selected = s == current,
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = NeonCyan.copy(alpha = 0.15f),
                            unselectedContainerColor = Color.Transparent
                        ),
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                        onClick = {
                            // close the drawer first, then switch screens
                            scope.launch {
                                drawerState.close()
                                onSelect(s)
                            }
                        }
                    )
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "AP Mode POIs",
                        color = NeonCyan, fontSize = 14.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Text("POI 1: ${PoiState.ip1}", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                    Text("POI 2: ${PoiState.ip2}", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                }
            }
        }
    ) {
        Column(Modifier.fillMaxSize()) {
            // header with hamburger
            val transition = rememberInfiniteTransition(label = "header")
            val shift by transition.animateFloat(
                initialValue = 0f, targetValue = 1000f,
                animationSpec = infiniteRepeatable(tween(6000), RepeatMode.Reverse),
                label = "shift"
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .background(
                        Brush.linearGradient(
                            0f to NeonCyan, 0.5f to NeonMagenta, 1f to NeonYellow,
                            start = Offset(shift, 0f), end = Offset(shift + 600f, 200f)
                        )
                    )
            ) {
                IconButton(
                    onClick = { scope.launch { drawerState.open() } },
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Icon(Icons.Filled.Menu, "Menu", tint = Color.Black)
                }
                Text(
                    current.title,
                    fontSize = 24.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                    color = Color.Black,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            content()
        }
    }
}
