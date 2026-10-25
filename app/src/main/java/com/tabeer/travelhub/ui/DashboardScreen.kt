package com.tabeer.travelhub.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tabeer.travelhub.browser.WebData
import com.tabeer.travelhub.data.*

fun openPortal(vm: AppViewModel, context: Context, p: Portal) {
    if (!p.enabled) { vm.toast("This portal is disabled. Enable it from the menu."); return }
    vm.markOpened(p.id)
    if (p.openMode == OpenMode.EXTERNAL) openExternal(context, p.url)
    else vm.screen = Screen.Browser(p.id)
}

fun openExternal(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "No app can open this link", android.widget.Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val s = vm.settings
    val shown = remember(vm.portals, vm.query, vm.categoryFilter, vm.favoritesOnly, s.showDisabled, s.sort) {
        PortalFilter.apply(vm.portals, vm.query, vm.categoryFilter, vm.favoritesOnly, s.showDisabled, s.sort)
    }
    val categories = remember(vm.portals) { PortalFilter.categories(vm.portals) }
    var menu by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Portal?>(null) }
    var toClear by remember { mutableStateOf<Portal?>(null) }

    val actions = PortalActions(
        open = { openPortal(vm, context, it) },
        edit = { vm.screen = Screen.Form(it.id) },
        external = { openExternal(context, it.url) },
        toggleFavorite = { p -> vm.modify(p.id) { it.copy(favorite = !it.favorite) } },
        togglePinned = { p -> vm.modify(p.id) { it.copy(pinned = !it.pinned) } },
        toggleEnabled = { p -> vm.modify(p.id) { it.copy(enabled = !it.enabled) } },
        moveUp = { vm.move(it.id, -1) },
        moveDown = { vm.move(it.id, 1) },
        clearSession = { toClear = it },
        delete = { toDelete = it },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TravelHub") },
                actions = {
                    if (vm.pinSet) IconButton(onClick = { vm.lockNow() }) { Icon(Icons.Filled.Lock, "Lock app") }
                    IconButton(onClick = { vm.screen = Screen.Settings }) { Icon(Icons.Filled.Settings, "Settings") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "View and sort options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(if (s.view == ViewMode.LIST) "Switch to grid view" else "Switch to list view") },
                                onClick = { menu = false; vm.updateSettings { it.copy(view = if (it.view == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST) } },
                            )
                            HorizontalDivider()
                            SortMode.entries.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text((if (s.sort == m) "✓ " else "    ") + "Sort: " + m.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                    onClick = { menu = false; vm.updateSettings { it.copy(sort = m) } },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(if (s.showDisabled) "Hide disabled portals" else "Show disabled portals") },
                                onClick = { menu = false; vm.updateSettings { it.copy(showDisabled = !it.showDisabled) } },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (vm.portals.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { vm.screen = Screen.Form(null) },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("Add portal") },
                )
            }
        },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            if (vm.portals.isEmpty()) {
                EmptyState { vm.screen = Screen.Form(null) }
            } else {
                OutlinedTextField(
                    value = vm.query,
                    onValueChange = { vm.query = it },
                    placeholder = { Text("Search by name or website") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = DefaultCorner,
                )
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = vm.categoryFilter == null && !vm.favoritesOnly,
                            onClick = { vm.categoryFilter = null; vm.favoritesOnly = false },
                            label = { Text("All") },
                        )
                    }
                    item {
                        FilterChip(
                            selected = vm.favoritesOnly,
                            onClick = { vm.favoritesOnly = !vm.favoritesOnly },
                            label = { Text("Favorites") },
                        )
                    }
                    items(categories) { c ->
                        FilterChip(
                            selected = vm.categoryFilter.equals(c, true),
                            onClick = { vm.categoryFilter = if (vm.categoryFilter.equals(c, true)) null else c },
                            label = { Text(c) },
                        )
                    }
                }
                if (shown.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No portals match your filters", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (s.view == ViewMode.GRID) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(168.dp),
                        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        gridItems(shown, key = { it.id }) { p ->
                            PortalCard(p, vm.credentialIds.contains(p.id), true, s.sort == SortMode.MANUAL, actions)
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(shown, key = { it.id }) { p ->
                            PortalCard(p, vm.credentialIds.contains(p.id), false, s.sort == SortMode.MANUAL, actions)
                        }
                    }
                }
            }
        }
    }

    toDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete “${p.name}”?") },
            text = { Text("This permanently removes the portal and its saved login from this device. This can't be undone.") },
            confirmButton = { TextButton(onClick = { vm.deletePortal(p); toDelete = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
    toClear?.let { p ->
        AlertDialog(
            onDismissRequest = { toClear = null },
            title = { Text("Clear session for “${p.name}”?") },
            text = {
                Text(
                    "Signs you out and removes this portal's cookies and site data (best effort). Android's WebView " +
                        "shares one cookie store across portals, so unusual cookies may remain; use Settings → Clear all browsing data to remove everything."
                )
            },
            confirmButton = { TextButton(onClick = { WebData.clearPortal(p); vm.toast("Session data cleared"); toClear = null }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { toClear = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🧭", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(12.dp))
        Text("No portals yet", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Add the travel websites you use. TravelHub keeps their logins in an encrypted vault and opens them in one tap.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAdd) {
            Icon(Icons.Filled.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("Add your first portal")
        }
    }
}

class PortalActions(
    val open: (Portal) -> Unit,
    val edit: (Portal) -> Unit,
    val external: (Portal) -> Unit,
    val toggleFavorite: (Portal) -> Unit,
    val togglePinned: (Portal) -> Unit,
    val toggleEnabled: (Portal) -> Unit,
    val moveUp: (Portal) -> Unit,
    val moveDown: (Portal) -> Unit,
    val clearSession: (Portal) -> Unit,
    val delete: (Portal) -> Unit,
)

@Composable
private fun PortalCard(p: Portal, hasLogin: Boolean, compact: Boolean, manual: Boolean, a: PortalActions) {
    var menu by remember { mutableStateOf(false) }
    ElevatedCard(
        shape = DefaultCorner,
        modifier = Modifier.fillMaxWidth().clickable { a.open(p) },
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (p.enabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PortalAvatar(p)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(p.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More options for ${p.name}") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        @Composable fun item(label: String, onClick: () -> Unit) =
                            DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; onClick() })
                        item("Open") { a.open(p) }
                        item("Open in external browser") { a.external(p) }
                        item("Edit / login settings") { a.edit(p) }
                        item(if (p.favorite) "Remove from favorites" else "Add to favorites") { a.toggleFavorite(p) }
                        item(if (p.pinned) "Unpin" else "Pin to top") { a.togglePinned(p) }
                        item(if (p.enabled) "Disable" else "Enable") { a.toggleEnabled(p) }
                        if (manual) {
                            item("Move up") { a.moveUp(p) }
                            item("Move down") { a.moveDown(p) }
                        }
                        item("Clear session data") { a.clearSession(p) }
                        item("Delete…") { a.delete(p) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            val tags = buildList {
                if (p.category.isNotBlank()) add(p.category)
                if (p.favorite) add("♥ Favorite")
                if (p.pinned) add("★ Pinned")
                if (hasLogin) add("🔒 Login saved")
                if (!p.enabled) add("Disabled")
            }
            if (tags.isNotEmpty()) {
                Text(tags.joinToString("  ·  "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (compact) 2 else 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { a.open(p) }, enabled = p.enabled) { Text("Open") }
                IconButton(onClick = { a.edit(p) }) { Icon(Icons.Filled.Edit, "Edit ${p.name}") }
            }
        }
    }
}
