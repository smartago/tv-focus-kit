package com.smartago.tvfocus.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartago.tvfocus.BackClaim
import com.smartago.tvfocus.FocusNav
import com.smartago.tvfocus.FocusRingHost
import com.smartago.tvfocus.focusId
import com.smartago.tvfocus.focusRingTarget
import com.smartago.tvfocus.keyboardInputMode
import com.smartago.tvfocus.navAudit
import com.smartago.tvfocus.navKeys
import com.smartago.tvfocus.pointerFocus

/**
 * A rail on the left, a grid on the right — the shape of every TV launcher — driven entirely
 * by the kit: one traveling ring, every arrow ruled, pointer and D-pad sharing the focus.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FocusNav.auditGaps = true          // watch logcat for "NAV GAP" while you press keys
        setContent { Demo() }
    }
}

private val RAIL = listOf("Home", "Apps", "Games", "Settings")

@Composable
private fun Demo() {
    var status by remember { mutableStateOf("Move with the D-pad. Hover with a mouse. Press BACK.") }
    // The outer BackHandler asks BackClaim first: a navKeys rule that already acted wins.
    BackHandler { if (!BackClaim.consume()) status = "System BACK reached the root" }

    FocusRingHost(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .keyboardInputMode()            // a tap on nothing must not leave the window in touch mode
            .navAudit(),                    // an arrow nobody ruled reports itself here
    ) {
        Row(Modifier.fillMaxSize().padding(32.dp)) {
            Column(Modifier.width(200.dp)) {
                RAIL.forEachIndexed { i, label ->
                    Tile(
                        label,
                        Modifier
                            .focusId("rail-" + i)
                            .navKeys(
                                up = if (i > 0) "rail-" + (i - 1) else "rail-0",      // a rule naming itself = a wall
                                down = if (i < RAIL.lastIndex) "rail-" + (i + 1) else "rail-" + i,
                                right = "tile-0",
                                backTo = "rail-0",
                            ),
                        onClick = { status = label + " pressed" },
                    )
                }
            }
            Column(Modifier.padding(start = 32.dp)) {
                Text(status, color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(bottom = 16.dp))
                for (row in 0 until 3) Row {
                    for (col in 0 until 4) {
                        val n = row * 4 + col
                        Tile(
                            "Tile " + n,
                            Modifier
                                .focusId("tile-" + n)
                                .navKeys(
                                    left = if (col > 0) "tile-" + (n - 1) else "rail-0",
                                    right = if (col < 3) "tile-" + (n + 1) else "tile-" + n,
                                    up = if (row > 0) "tile-" + (n - 4) else "tile-" + n,
                                    down = if (row < 2) "tile-" + (n + 4) else "tile-" + n,
                                    onBack = { status = "BACK on Tile " + n + " - claimed, the root never saw it" },
                                ),
                            onClick = { status = "Tile " + n + " pressed" },
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { FocusNav.focusSticky("rail-0") }
}

@Composable
private fun Tile(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .padding(8.dp)
            .size(width = 150.dp, height = 84.dp)
            .focusRingTarget(corner = 14.dp)   // the window ring wraps it; no per-tile focus border
            .pointerFocus()                    // hover or tap moves the SAME focus the D-pad uses
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1E293B))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 16.sp)
    }
}
