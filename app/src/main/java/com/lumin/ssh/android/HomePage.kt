package com.lumin.ssh.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState

@Composable
fun HomePage(
    connections: List<Connection>,
    orderedGroups: List<String>,
    groupedConnections: Map<String, List<Connection>>,
    collapsedGroups: SnapshotStateList<String>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    hideSensitive: Boolean,
    onToggleHideSensitive: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddServer: () -> Unit,
    onOpenCredentials: () -> Unit,
    onCollapseAll: () -> Unit,
    onExpandAll: () -> Unit,
    onConnect: (Connection) -> Unit,
    onEdit: (Connection) -> Unit,
    onDelete: (Connection) -> Unit,
    onGroupDragStarted: () -> Unit,
    onGroupDragStopped: () -> Unit,
    homeLazyListState: LazyListState,
    homeReorderableState: ReorderableLazyListState,
) {
    val allCollapsed = orderedGroups.isNotEmpty() && orderedGroups.all { it in collapsedGroups }
    LazyColumn(
        Modifier.fillMaxSize().background(LuminColors.SurfaceBase).padding(horizontal = 16.dp, vertical = 12.dp),
        state = homeLazyListState,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            LuminPageHeader(
                title = stringResource(R.string.app_name),
                subtitle = stringResource(R.string.server_count, connections.size),
            ) {
                LuminSecondaryButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.settings_title))
                }
                LuminPrimaryButton(onClick = onAddServer) {
                    Text(stringResource(R.string.add))
                }
            }
        }
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                label = { Text(stringResource(R.string.search_servers)) },
                modifier = Modifier.fillMaxWidth(),
                shape = LuminControlShape,
                colors = luminTextFieldColors(),
                singleLine = true,
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LuminSecondaryButton(onClick = onToggleHideSensitive, modifier = Modifier.weight(1f)) {
                    Text(if (hideSensitive) stringResource(R.string.show_ip_short) else stringResource(R.string.hide_ip_short))
                }
                LuminSecondaryButton(onClick = onOpenCredentials, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.credentials))
                }
                if (orderedGroups.isNotEmpty()) {
                    LuminSecondaryButton(
                        onClick = { if (allCollapsed) onExpandAll() else onCollapseAll() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (allCollapsed) stringResource(R.string.expand_groups) else stringResource(R.string.collapse_groups))
                    }
                }
            }
        }
        if (orderedGroups.isEmpty()) {
            item {
                LuminEmptyState(
                    title = stringResource(R.string.no_servers_title),
                    description = stringResource(R.string.no_servers_hint),
                    actionLabel = stringResource(R.string.add),
                    onAction = onAddServer,
                )
            }
        }
        orderedGroups.forEach { groupName ->
            val groupConnections = groupedConnections[groupName].orEmpty()
            val collapsed = collapsedGroups.contains(groupName)
            item(key = "group-$groupName") {
                ReorderableItem(homeReorderableState, key = "group-$groupName") { isDragging ->
                    Card(
                        Modifier.fillMaxWidth().alpha(if (isDragging) 0.75f else 1f),
                        shape = LuminCardShape,
                        colors = CardDefaults.cardColors(containerColor = LuminColors.SurfaceOverlay),
                        border = BorderStroke(1.dp, LuminColors.BorderSubtle),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .longPressDraggableHandle(
                                        onDragStarted = { onGroupDragStarted() },
                                        onDragStopped = { onGroupDragStopped() },
                                    )
                                    .clickable {
                                        if (collapsedGroups.contains(groupName)) collapsedGroups.remove(groupName)
                                        else collapsedGroups.add(groupName)
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(if (collapsed) "▸" else "▾", color = LuminColors.Accent)
                                Spacer(Modifier.size(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(groupName, style = MaterialTheme.typography.titleMedium, color = LuminColors.TextPrimary)
                                    Text(
                                        stringResource(R.string.server_count, groupConnections.size),
                                        color = LuminColors.TextMuted,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Text("⋮⋮", color = LuminColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                            }
                            if (!collapsed) {
                                groupConnections.forEachIndexed { index, conn ->
                                    if (index > 0) Spacer(Modifier.height(2.dp))
                                    CompactConnectionRow(
                                        conn = conn,
                                        onConnect = { onConnect(conn) },
                                        onEdit = { onEdit(conn) },
                                        onDelete = { onDelete(conn) },
                                        hideSensitive = hideSensitive,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}
