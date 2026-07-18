package com.lumin.ssh.android

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.json.JSONArray
import org.json.JSONObject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

fun quickCommandFolderPathsForUi(nodes: List<QuickCommandNode>): List<String> = nodes.flatMap { node ->
    when (node) {
        is QuickCommandNode.Command -> emptyList()
        is QuickCommandNode.Folder -> listOf(node.path) + quickCommandFolderPathsForUi(node.children)
    }
}

fun quickCommandFolderOptions(nodes: List<QuickCommandNode>, rootLabel: String): List<Pair<String, List<Int>>> = listOf<Pair<String, List<Int>>>(rootLabel to emptyList()) + nodes.flatMap { node ->
    when (node) {
        is QuickCommandNode.Command -> emptyList()
        is QuickCommandNode.Folder -> listOf(node.path to node.indexPath) + quickCommandFolderOptions(node.children, rootLabel).drop(1)
    }
}

data class QuickCommandVisibleNode(val node: QuickCommandNode, val depth: Int) {
    val key: String = when (node) {
        is QuickCommandNode.Folder -> "folder:${node.path}"
        is QuickCommandNode.Command -> "command:${node.item.group}/${node.item.name}/${node.item.command.hashCode()}"
    }
    val indexPath: List<Int> get() = when (node) {
        is QuickCommandNode.Folder -> node.indexPath
        is QuickCommandNode.Command -> node.indexPath
    }
}

fun visibleQuickCommandNodes(nodes: List<QuickCommandNode>, collapsedFolders: List<String>, depth: Int = 0): List<QuickCommandVisibleNode> = nodes.flatMap { node ->
    val current = listOf(QuickCommandVisibleNode(node, depth))
    if (node is QuickCommandNode.Folder && !collapsedFolders.contains(node.path)) current + visibleQuickCommandNodes(node.children, collapsedFolders, depth + 1) else current
}

fun quickCommandSiblingDropIndex(nodes: List<QuickCommandVisibleNode>, key: String?): Int? {
    if (key == null) return null
    val target = nodes.firstOrNull { it.key == key } ?: return null
    return nodes.filter { it.indexPath.dropLast(1) == target.indexPath.dropLast(1) }.indexOfFirst { it.key == key }.takeIf { it >= 0 }
}

fun quickCommandRoot(json: String) = runCatching { JSONArray(json.ifBlank { "[]" }) }.getOrElse { JSONArray() }

fun quickCommandObjectAt(root: JSONArray, indexPath: List<Int>): JSONObject? {
    if (indexPath.isEmpty()) return null
    var array = root
    var target = array.optJSONObject(indexPath.first()) ?: return null
    for (depth in 1 until indexPath.size) {
        array = target.optJSONArray("children") ?: return null
        target = array.optJSONObject(indexPath[depth]) ?: return null
    }
    return target
}

fun quickCommandParentArray(root: JSONArray, indexPath: List<Int>): JSONArray? {
    if (indexPath.isEmpty()) return root
    val parentPath = indexPath.dropLast(1)
    if (parentPath.isEmpty()) return root
    return quickCommandObjectAt(root, parentPath)?.optJSONArray("children")
}

fun updateQuickCommandJson(json: String, indexPath: List<Int>, name: String, command: String, addCR: Boolean): String {
    val root = quickCommandRoot(json)
    val target = quickCommandObjectAt(root, indexPath) ?: return json
    target.put("name", name)
    target.put("command", command)
    target.put("addCR", addCR)
    target.put("last_modified", System.currentTimeMillis())
    return root.toString()
}

fun updateOrMoveQuickCommandJson(json: String, indexPath: List<Int>, parentPath: List<Int>, name: String, command: String, addCR: Boolean): String {
    if (indexPath.dropLast(1) == parentPath) return updateQuickCommandJson(json, indexPath, name, command, addCR)
    val root = quickCommandRoot(json)
    val targetParentArray = if (parentPath.isEmpty()) root else quickCommandObjectAt(root, parentPath)?.optJSONArray("children") ?: return json
    val oldParentArray = quickCommandParentArray(root, indexPath) ?: return json
    oldParentArray.remove(indexPath.lastOrNull() ?: return json)
    targetParentArray.put(JSONObject().put("name", name).put("command", command).put("addCR", addCR).put("last_modified", System.currentTimeMillis()))
    return root.toString()
}

fun updateQuickCommandFolderJson(json: String, indexPath: List<Int>, name: String): String {
    val root = quickCommandRoot(json)
    val target = quickCommandObjectAt(root, indexPath) ?: return json
    target.put("name", name)
    target.put("last_modified", System.currentTimeMillis())
    return root.toString()
}

fun addQuickCommandJson(json: String, parentPath: List<Int>, name: String, command: String, addCR: Boolean): String {
    val root = quickCommandRoot(json)
    val parentArray = if (parentPath.isEmpty()) root else quickCommandObjectAt(root, parentPath)?.optJSONArray("children") ?: return json
    parentArray.put(JSONObject().put("name", name).put("command", command).put("addCR", addCR).put("last_modified", System.currentTimeMillis()))
    return root.toString()
}

fun addQuickCommandFolderJson(json: String, parentPath: List<Int>, name: String): String {
    val root = quickCommandRoot(json)
    val parentArray = if (parentPath.isEmpty()) root else quickCommandObjectAt(root, parentPath)?.optJSONArray("children") ?: return json
    parentArray.put(JSONObject().put("type", "group").put("name", name).put("expanded", true).put("children", JSONArray()).put("last_modified", System.currentTimeMillis()))
    return root.toString()
}

fun deleteQuickCommandJson(json: String, indexPath: List<Int>): String {
    val root = quickCommandRoot(json)
    val parentArray = quickCommandParentArray(root, indexPath) ?: return json
    parentArray.remove(indexPath.lastOrNull() ?: return json)
    return root.toString()
}

fun moveQuickCommandJson(json: String, fromPath: List<Int>, toIndex: Int): String {
    if (fromPath.isEmpty()) return json
    val root = quickCommandRoot(json)
    val parentArray = quickCommandParentArray(root, fromPath) ?: return json
    val fromIndex = fromPath.last()
    if (fromIndex !in 0 until parentArray.length() || toIndex !in 0 until parentArray.length() || fromIndex == toIndex) return json
    val objects = (0 until parentArray.length()).mapNotNull { parentArray.optJSONObject(it) }.toMutableList()
    val moved = objects.removeAt(fromIndex)
    objects.add(toIndex, moved)
    while (parentArray.length() > 0) parentArray.remove(0)
    objects.forEach { parentArray.put(it) }
    moved.put("last_modified", System.currentTimeMillis())
    return root.toString()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuickCommandManagerPage(quickCommandsRaw: String, onBack: () -> Unit, onSave: (String, Boolean) -> Unit) {
    val context = LocalContext.current
    var workingQuickCommandsRaw by remember { mutableStateOf(quickCommandsRaw) }
    val workingQuickCommandsRef = remember { mutableStateOf(quickCommandsRaw) }
    LaunchedEffect(quickCommandsRaw) {
        if (quickCommandsRaw != workingQuickCommandsRef.value) {
            workingQuickCommandsRaw = quickCommandsRaw
            workingQuickCommandsRef.value = quickCommandsRaw
        }
    }
    val savedMessage = stringResource(R.string.save)
    fun saveQuickCommands(updated: String, toastMsg: String = savedMessage, syncNow: Boolean = true) {
        workingQuickCommandsRef.value = updated
        workingQuickCommandsRaw = updated
        onSave(updated, syncNow)
        if (toastMsg.isNotEmpty()) {
            Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
        }
    }
    val tree = remember(workingQuickCommandsRaw) { quickCommandTreeFromJson(workingQuickCommandsRaw) }
    val commands = remember(workingQuickCommandsRaw) { quickCommandsFromJson(workingQuickCommandsRaw) }
    var isDragActive by remember { mutableStateOf(false) }
    val savedCollapsedFolders = remember { mutableListOf<String>() }
    val collapsedFolders = remember { mutableStateListOf<String>().apply { addAll(quickCommandFolderPathsForUi(tree)) } }
    LaunchedEffect(tree) {
        if (isDragActive) return@LaunchedEffect
        val folderPaths = quickCommandFolderPathsForUi(tree)
        collapsedFolders.removeAll { it !in folderPaths }
    }
    var editingNode by remember(workingQuickCommandsRaw) { mutableStateOf<QuickCommandNode.Command?>(null) }
    var editingFolder by remember(workingQuickCommandsRaw) { mutableStateOf<QuickCommandNode.Folder?>(null) }
    var addingCommandParent by remember { mutableStateOf<List<Int>?>(null) }
    var addingFolderParent by remember { mutableStateOf<List<Int>?>(null) }
    var selectedParentPath by remember { mutableStateOf<List<Int>>(emptyList()) }
    var selectedParentExpanded by remember { mutableStateOf(false) }
    var folderMenuPath by remember { mutableStateOf<List<Int>?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Pair<String, List<Int>>?>(null) }
    var editName by remember { mutableStateOf("") }
    var editCommand by remember { mutableStateOf("") }
    var editAddCR by remember { mutableStateOf(true) }
    var editFolderName by remember { mutableStateOf("") }

    fun startEdit(node: QuickCommandNode.Command) {
        editingNode = node
        editingFolder = null
        addingCommandParent = null
        addingFolderParent = null
        selectedParentPath = node.indexPath.dropLast(1)
        editName = node.item.name
        editCommand = node.item.command
        editAddCR = node.item.addCR
    }

    fun startAddCommand(parentPath: List<Int>) {
        editingNode = null
        editingFolder = null
        addingCommandParent = parentPath
        addingFolderParent = null
        selectedParentPath = parentPath
        editName = ""
        editCommand = ""
        editAddCR = true
    }

    fun startEditFolder(node: QuickCommandNode.Folder) {
        editingFolder = node
        editingNode = null
        addingCommandParent = null
        addingFolderParent = null
        editFolderName = node.name
    }

    fun startAddFolder(parentPath: List<Int>) {
        editingFolder = null
        editingNode = null
        addingCommandParent = null
        addingFolderParent = parentPath
        selectedParentPath = parentPath
        editFolderName = ""
    }

    fun addParameterPlaceholder() {
        val nextIndex = Regex("\\[p#(\\d+)").findAll(editCommand)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .maxOrNull()
            ?.plus(1) ?: 1
        editCommand += if (editCommand.endsWith(" ") || editCommand.isBlank()) "[p#$nextIndex ${context.getString(R.string.parameter_placeholder, nextIndex)}]" else " [p#$nextIndex ${context.getString(R.string.parameter_placeholder, nextIndex)}]"
    }

    val rootFolderLabel = stringResource(R.string.root_folder)
    val folderOptions = remember(tree, rootFolderLabel) { quickCommandFolderOptions(tree, rootFolderLabel) }
    val visibleNodes = visibleQuickCommandNodes(tree, collapsedFolders)
    val displayNodeList = remember { mutableStateListOf<QuickCommandVisibleNode>().apply { addAll(visibleNodes) } }
    LaunchedEffect(visibleNodes, isDragActive) {
        if (!isDragActive) {
            val currentKeys = displayNodeList.map { it.key }
            val newKeys = visibleNodes.map { it.key }
            if (currentKeys != newKeys) {
                displayNodeList.clear()
                displayNodeList.addAll(visibleNodes)
            }
        }
    }
    var searchQuery by remember { mutableStateOf("") }
    val displayedNodes = if (searchQuery.isBlank()) displayNodeList else visibleQuickCommandNodes(tree, emptyList()).filter { vn ->
        vn.node is QuickCommandNode.Command &&
            (vn.node.item.name.contains(searchQuery, ignoreCase = true) || vn.node.item.command.contains(searchQuery, ignoreCase = true))
    }
    val lazyListState = rememberLazyListState()
    var dragStartKey by remember { mutableStateOf<String?>(null) }
    var dragStartPath by remember { mutableStateOf<List<Int>?>(null) }
    var dragDropIndex by remember { mutableStateOf<Int?>(null) }
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        if (searchQuery.isNotBlank()) return@rememberReorderableLazyListState
        val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
        val toKey = to.key as? String ?: return@rememberReorderableLazyListState
        val fromItem = displayNodeList.firstOrNull { it.key == fromKey } ?: return@rememberReorderableLazyListState
        val toItem = displayNodeList.firstOrNull { it.key == toKey } ?: return@rememberReorderableLazyListState
        if (toItem.indexPath.dropLast(1) != fromItem.indexPath.dropLast(1)) return@rememberReorderableLazyListState
        val fromIndex = displayNodeList.indexOfFirst { it.key == fromKey }
        val toIndex = displayNodeList.indexOfFirst { it.key == toKey }
        if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return@rememberReorderableLazyListState
        if (dragStartKey == null) dragStartKey = fromKey
        displayNodeList.add(toIndex, displayNodeList.removeAt(fromIndex))
        dragDropIndex = quickCommandSiblingDropIndex(displayNodeList, dragStartKey)
    }
    fun selectedParentName() = folderOptions.firstOrNull { it.second == selectedParentPath }?.first ?: rootFolderLabel

    @Composable
    fun ParentFolderSelector() {
        Box {
            LuminPrimaryButton(onClick = { selectedParentExpanded = true }, modifier = Modifier.fillMaxWidth()){ Text(stringResource(R.string.folder_label, selectedParentName())) }
            DropdownMenu(expanded = selectedParentExpanded, onDismissRequest = { selectedParentExpanded = false }) {
                folderOptions.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.first) },
                        onClick = {
                            selectedParentPath = option.second
                            selectedParentExpanded = false
                        },
                    )
                }
            }
        }
    }

    @Composable
    fun AddTypeDialog() {
        if (!showAddDialog) return
        Dialog(onDismissRequest = { showAddDialog = false }) {
            LuminCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.add_quick_command), style = MaterialTheme.typography.titleLarge)
                    LuminPrimaryButton(onClick = { showAddDialog = false; startAddCommand(emptyList()) }, modifier = Modifier.fillMaxWidth()){ Text(stringResource(R.string.add_command)) }
                    LuminPrimaryButton(onClick = { showAddDialog = false; startAddFolder(emptyList()) }, modifier = Modifier.fillMaxWidth()){ Text(stringResource(R.string.add_folder)) }
                    LuminPrimaryButton(onClick = { showAddDialog = false }, modifier = Modifier.fillMaxWidth()){ Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }

    @Composable
    fun DeleteConfirmDialog() {
        val target = pendingDelete ?: return
        ConfirmDialog(
            title = stringResource(R.string.delete_quick_command),
            text = stringResource(R.string.delete_quick_command_message, target.first),
            onCancel = { pendingDelete = null },
            onConfirm = {
                saveQuickCommands(deleteQuickCommandJson(workingQuickCommandsRaw, target.second), context.getString(R.string.deleted_item, target.first))
                pendingDelete = null
                editingNode = null
                editingFolder = null
            },
        )
    }

    @Composable
    fun RenderVisibleNode(visibleNode: QuickCommandVisibleNode, isDragging: Boolean, dragModifier: Modifier) {
        val node = visibleNode.node
        val depth = visibleNode.depth
        when (node) {
            is QuickCommandNode.Command -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = (depth * 10).dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (depth > 0) {
                        Box(
                            Modifier.width(3.dp).height(72.dp).background(LuminColors.Accent.copy(alpha = 0.65f))
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    LuminCard(Modifier.weight(1f).alpha(if (isDragging) 0.75f else 1f).then(dragModifier).combinedClickable(onClick = { startEdit(node) })) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(node.item.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                node.item.command.lineSequence().firstOrNull().orEmpty().ifBlank { stringResource(R.string.quick_command_management_hint) },
                                color = LuminColors.TextMuted,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            is QuickCommandNode.Folder -> {
                val collapsed = collapsedFolders.contains(node.path)
                Card(
                    modifier = Modifier.fillMaxWidth().padding(start = (depth * 10).dp, top = 4.dp, bottom = 4.dp).alpha(if (isDragging) 0.75f else 1f).then(dragModifier),
                    shape = LuminControlShape, colors = CardDefaults.cardColors(containerColor = LuminColors.SurfaceSunken), border = androidx.compose.foundation.BorderStroke(1.dp, LuminColors.BorderSubtle),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (collapsed) "▸ ${node.name}" else "▾ ${node.name}",
                                color = LuminColors.Accent,
                                modifier = Modifier.weight(1f).clickable {
                                    if (collapsedFolders.contains(node.path)) collapsedFolders.remove(node.path) else collapsedFolders.add(node.path)
                                },
                            )
                            Box {
                                LuminPrimaryButton(onClick = { folderMenuPath = node.indexPath }){ Text("⋮") }
                                DropdownMenu(expanded = folderMenuPath == node.indexPath, onDismissRequest = { folderMenuPath = null }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.rename)) },
                                        onClick = {
                                            folderMenuPath = null
                                            startEditFolder(node)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.delete)) },
                                        onClick = {
                                            folderMenuPath = null
                                            pendingDelete = node.name to node.indexPath
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    BackHandler { if (editingNode != null || editingFolder != null || addingCommandParent != null || addingFolderParent != null) { editingNode = null; editingFolder = null; addingCommandParent = null; addingFolderParent = null } else onBack() }

    if (editingNode != null || addingCommandParent != null) {
        val isAdd = addingCommandParent != null
        LazyColumn(Modifier.fillMaxSize().background(LuminColors.SurfaceBase).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                LuminPageHeader(
                    title = if (isAdd) stringResource(R.string.new_quick_command) else stringResource(R.string.edit_quick_command),
                    onBack = { editingNode = null; addingCommandParent = null },
                    backLabel = stringResource(R.string.cancel),
                ) {
                    LuminPrimaryButton(onClick = {
                        val updated = if (isAdd) addQuickCommandJson(workingQuickCommandsRaw, selectedParentPath, editName, editCommand, editAddCR) else updateOrMoveQuickCommandJson(workingQuickCommandsRaw, editingNode?.indexPath ?: emptyList(), selectedParentPath, editName, editCommand, editAddCR)
                        saveQuickCommands(updated)
                        editingNode = null
                        addingCommandParent = null
                    }) { Text(stringResource(R.string.save)) }
                }
            }
            item { ParentFolderSelector() }
            item { OutlinedTextField(editName, { editName = it }, label = { Text(stringResource(R.string.name)) }, modifier = Modifier.fillMaxWidth(), shape = LuminControlShape, colors = luminTextFieldColors()) }
            item { OutlinedTextField(editCommand, { editCommand = it }, label = { Text(stringResource(R.string.command)) }, modifier = Modifier.fillMaxWidth().height(140.dp), shape = LuminControlShape, colors = luminTextFieldColors()) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    LuminSecondaryButton(onClick = { addParameterPlaceholder() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.add_parameter)) }
                    LuminChoiceChip(
                        label = if (editAddCR) stringResource(R.string.auto_enter_on) else stringResource(R.string.auto_enter_off),
                        selected = editAddCR,
                        onClick = { editAddCR = !editAddCR },
                    )
                }
            }
            item { Text(stringResource(R.string.parameter_format_hint), color = LuminColors.TextMuted, style = MaterialTheme.typography.bodySmall) }
            if (!isAdd) {
                item {
                    LuminDangerButton(
                        onClick = { editingNode?.let { pendingDelete = it.item.name to it.indexPath } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.delete)) }
                }
            }
        }
        DeleteConfirmDialog()
        return
    }

    if (editingFolder != null || addingFolderParent != null) {
        val isAdd = addingFolderParent != null
        LazyColumn(Modifier.fillMaxSize().background(LuminColors.SurfaceBase).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                LuminPageHeader(
                    title = if (isAdd) stringResource(R.string.new_folder) else stringResource(R.string.edit_folder),
                    onBack = { editingFolder = null; addingFolderParent = null },
                    backLabel = stringResource(R.string.cancel),
                ) {
                    LuminPrimaryButton(onClick = {
                        val updated = if (isAdd) addQuickCommandFolderJson(workingQuickCommandsRaw, emptyList(), editFolderName) else updateQuickCommandFolderJson(workingQuickCommandsRaw, editingFolder?.indexPath ?: emptyList(), editFolderName)
                        saveQuickCommands(updated)
                        editingFolder = null
                        addingFolderParent = null
                    }) { Text(stringResource(R.string.save)) }
                }
            }
            item { OutlinedTextField(editFolderName, { editFolderName = it }, label = { Text(stringResource(R.string.folder_name)) }, modifier = Modifier.fillMaxWidth(), shape = LuminControlShape, colors = luminTextFieldColors()) }
            if (!isAdd) {
                item {
                    LuminDangerButton(
                        onClick = { editingFolder?.let { pendingDelete = it.name to it.indexPath } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.delete)) }
                }
            }
        }
        DeleteConfirmDialog()
        return
    }

    LazyColumn(Modifier.fillMaxSize().background(LuminColors.SurfaceBase).padding(horizontal = 16.dp, vertical = 12.dp), state = lazyListState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            LuminPageHeader(
                title = stringResource(R.string.quick_command_management),
                subtitle = stringResource(R.string.quick_command_count, commands.size),
                onBack = onBack,
                backLabel = stringResource(R.string.back),
            ) {
                LuminPrimaryButton(onClick = { showAddDialog = true }) { Text(stringResource(R.string.add)) }
            }
        }
        item {
            OutlinedTextField(
                searchQuery,
                { searchQuery = it },
                label = { Text(stringResource(R.string.search_quick_commands)) },
                modifier = Modifier.fillMaxWidth(),
                shape = LuminControlShape,
                colors = luminTextFieldColors(),
                singleLine = true,
            )
        }
        if (tree.isEmpty()) {
            item {
                LuminEmptyState(title = stringResource(R.string.no_quick_commands), actionLabel = stringResource(R.string.add), onAction = { showAddDialog = true })
            }
        } else {
            itemsIndexed(displayedNodes, key = { _, item -> item.key }) { _, visibleNode ->
                ReorderableItem(reorderableState, key = visibleNode.key) { isDragging ->
                    val dragModifier = if (searchQuery.isBlank()) Modifier.longPressDraggableHandle(
                        onDragStarted = {
                            isDragActive = true
                            savedCollapsedFolders.clear()
                            savedCollapsedFolders.addAll(collapsedFolders)
                            val dragRootItem = visibleNode.indexPath.size == 1
                            if (dragRootItem) {
                                // ponytail: root-level drag -> collapse all folders so root drop targets stay stable.
                                collapsedFolders.clear()
                                collapsedFolders.addAll(quickCommandFolderPathsForUi(tree))
                            }
                            val latestVisibleNodes = visibleQuickCommandNodes(quickCommandTreeFromJson(workingQuickCommandsRef.value), collapsedFolders)
                            val latestNode = latestVisibleNodes.firstOrNull { it.key == visibleNode.key } ?: visibleNode
                            dragStartKey = latestNode.key
                            dragStartPath = latestNode.indexPath
                            dragDropIndex = quickCommandSiblingDropIndex(latestVisibleNodes, latestNode.key)
                            displayNodeList.clear()
                            displayNodeList.addAll(latestVisibleNodes)
                        },
                        onDragStopped = {
                            isDragActive = false
                            val startPath = dragStartPath
                            val targetIndex = dragDropIndex
                            if (startPath != null && targetIndex != null && startPath.lastOrNull() != targetIndex) {
                                saveQuickCommands(moveQuickCommandJson(workingQuickCommandsRef.value, startPath, targetIndex), "", syncNow = true)
                            }
                            dragStartKey = null
                            dragStartPath = null
                            dragDropIndex = null
                            collapsedFolders.clear()
                            collapsedFolders.addAll(savedCollapsedFolders)
                            savedCollapsedFolders.clear()
                            displayNodeList.clear()
                            displayNodeList.addAll(visibleQuickCommandNodes(quickCommandTreeFromJson(workingQuickCommandsRef.value), collapsedFolders))
                        },
                    ) else Modifier
                    RenderVisibleNode(visibleNode, isDragging, dragModifier)
                }
            }
        }
    }
    AddTypeDialog()
    DeleteConfirmDialog()
}
