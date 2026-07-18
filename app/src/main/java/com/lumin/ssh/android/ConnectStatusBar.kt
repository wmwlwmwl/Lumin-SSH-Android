package com.lumin.ssh.android

import androidx.compose.foundation.background
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun ConnectStatusBar(connecting: Boolean, conn: Connection, connectDetails: List<String>) {
    if (!connecting) return
    Box(modifier = Modifier.fillMaxSize().background(LuminColors.TerminalBg.copy(alpha = 0.94f))) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Lumin SSH", color = LuminColors.TerminalText, style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(28.dp))
            Text(stringResource(R.string.connecting), color = LuminColors.TerminalText, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = LuminColors.TerminalCursor, strokeWidth = 2.dp)
                Text("${conn.username}@${conn.host}:${conn.port}", color = LuminColors.TerminalTextMuted)
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(LuminColors.TerminalBar.copy(alpha = 0.92f))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            connectDetails.takeLast(8).forEach { detail ->
                Text("ssh_connect: $detail", color = LuminColors.TerminalTextMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
