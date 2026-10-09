package com.xxxx.emby_tv.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.xxxx.emby_tv.R
import com.xxxx.emby_tv.data.model.BaseItemDto
import com.xxxx.emby_tv.data.remote.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun OnlineSubtitlesTab(mediaInfo: BaseItemDto, onDownloaded: suspend (LocalSubtitle) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val searchFocus = remember { FocusRequester() }
    var query by remember(mediaInfo.id) { mutableStateOf(mediaInfo.seriesName ?: mediaInfo.name.orEmpty()) }
    var editing by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<AssrtSearchResult>>(emptyList()) }
    var files by remember { mutableStateOf<List<AssrtFile>?>(null) }
    var searched by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val failed = stringResource(R.string.online_subtitle_search_failed)

    fun request(action: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: failed }
            finally { busy = false }
        }
    }

    if (editing) TvInputDialog(
        title = stringResource(R.string.assrt_keyword), initialValue = query,
        onConfirm = { query = it.trim(); searched = false; results = emptyList(); files = null; error = null },
        onDismiss = { editing = false }
    )

    LazyColumn(contentPadding = PaddingValues(horizontal = 150.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "search") {
            Text(stringResource(R.string.assrt_attribution))
            Text(query)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { if (!busy) editing = true }) { Text(stringResource(R.string.assrt_keyword)) }
                Button(onClick = { request {
                    val found = AssrtSubtitleService.search(context, query)
                    results = found; files = null; searched = true
                } }, enabled = query.length >= 3, modifier = Modifier.focusRequester(searchFocus)) { Text(stringResource(R.string.online_subtitle_search)) }
            }
        }
        if (busy) item(key = "busy") { Text(stringResource(R.string.assrt_loading)) }
        error?.let { item(key = "error") { Text(it, color = Color(0xFFFFB4AB), modifier = Modifier.focusable()) } }
        if (files != null) {
            item(key = "back") { Button(onClick = { if (!busy) { searchFocus.requestFocus(); files = null } }) { Text(stringResource(R.string.assrt_back_results)) } }
            if (files!!.isEmpty()) item { Text(stringResource(R.string.assrt_no_files), modifier = Modifier.focusable()) }
            items(files.orEmpty(), key = { it.url }) { file ->
                Button(onClick = { request { onDownloaded(AssrtSubtitleService.download(context, file)) } },
                    modifier = Modifier.fillMaxWidth()) { Text(file.name) }
            }
        } else {
            if (searched && results.isEmpty()) item { Text(stringResource(R.string.assrt_empty), modifier = Modifier.focusable()) }
            items(results, key = { it.id }) { result ->
                Button(onClick = { request { val found = AssrtSubtitleService.files(context, result.id); searchFocus.requestFocus(); files = found } },
                    modifier = Modifier.fillMaxWidth()) {
                    Column { Text(result.name); Text(listOf(result.language, result.format).filter { it.isNotBlank() }.joinToString(" · ")) }
                }
            }
        }
        item {
            Button(onClick = {
                runCatching {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://assrt.net")))
                }.onFailure { error = failed }
            }) { Text("assrt.net") }
        }
    }
}
