package com.xxxx.emby_tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.xxxx.emby_tv.R
import com.xxxx.emby_tv.data.model.RemoteSubtitleInfo
import com.xxxx.emby_tv.data.repository.EmbyRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun OnlineSubtitlesTab(
    mediaId: String,
    mediaSourceId: String?,
    repository: EmbyRepository,
    onDownloaded: suspend (Int) -> Unit
) {
    val scope = rememberCoroutineScope()
    var language by remember(mediaId) { mutableStateOf("chi") }
    var showLanguageInput by remember { mutableStateOf(false) }
    var results by remember(mediaId) { mutableStateOf<List<RemoteSubtitleInfo>>(emptyList()) }
    var searched by remember(mediaId) { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var downloadedIndex by remember(mediaId) { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val busy = searching || downloadingId != null
    val searchFailedText = stringResource(R.string.online_subtitle_search_failed)
    val downloadFailedText = stringResource(R.string.online_subtitle_download_failed)
    val invalidLanguageText = stringResource(R.string.online_subtitle_invalid_language)

    fun search() {
        if (busy || mediaId.isBlank()) return
        scope.launch {
            searching = true
            error = null
            results = emptyList()
            searched = false
            downloadedIndex = null
            try {
                results = repository.searchRemoteSubtitles(mediaId, mediaSourceId, language)
                searched = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: searchFailedText
            } finally {
                searching = false
            }
        }
    }

    if (showLanguageInput) {
        TvInputDialog(
            title = stringResource(R.string.online_subtitle_language_code),
            initialValue = language,
            onConfirm = { input ->
                val code = input.trim().lowercase(Locale.ROOT)
                if (code.matches(Regex("[a-z]{2,3}(-[a-z]{2,8})?"))) {
                    language = code
                    results = emptyList()
                    searched = false
                    downloadedIndex = null
                    error = null
                } else {
                    error = invalidLanguageText
                }
            },
            onDismiss = { showLanguageInput = false }
        )
    }

    LazyColumn(contentPadding = PaddingValues(horizontal = 150.dp, vertical = 8.dp)) {
        item {
            Text(
                text = stringResource(R.string.online_subtitle_language, language),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { language = "chi"; results = emptyList(); searched = false; downloadedIndex = null }, enabled = !busy) {
                    Text(stringResource(R.string.online_subtitle_chinese))
                }
                Button(onClick = { language = "eng"; results = emptyList(); searched = false; downloadedIndex = null }, enabled = !busy) {
                    Text(stringResource(R.string.online_subtitle_english))
                }
                Button(onClick = { showLanguageInput = true }, enabled = !busy) {
                    Text(stringResource(R.string.online_subtitle_other_language))
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = ::search, enabled = !busy && mediaId.isNotBlank()) {
                Text(stringResource(R.string.online_subtitle_search))
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
        if (searching || downloadingId != null) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = Color.White)
                    Text(stringResource(if (searching) R.string.online_subtitle_searching else R.string.online_subtitle_downloading))
                }
            }
        }
        error?.let { message ->
            item { Text(message, color = Color(0xFFFFB4AB)) }
        }
        downloadedIndex?.let { index ->
            if (error != null) {
                item {
                    Button(onClick = {
                        scope.launch {
                            downloadingId = "retry"
                            error = null
                            try {
                                onDownloaded(index)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message ?: downloadFailedText
                            } finally {
                                downloadingId = null
                            }
                        }
                    }, enabled = !busy) {
                        Text(stringResource(R.string.online_subtitle_retry_switch))
                    }
                }
            }
        }
        if (searched && results.isEmpty()) {
            item { Text(stringResource(R.string.online_subtitle_empty)) }
        }
        items(results) { result ->
            val subtitleId = result.id
            Surface(
                onClick = {
                    if (subtitleId != null && !busy) {
                        scope.launch {
                            downloadingId = subtitleId
                            downloadedIndex = null
                            error = null
                            try {
                                val index = repository.downloadRemoteSubtitle(mediaId, mediaSourceId, subtitleId)
                                downloadedIndex = index
                                onDownloaded(index)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message ?: downloadFailedText
                            } finally {
                                downloadingId = null
                            }
                        }
                    }
                },
                enabled = subtitleId != null && !busy,
                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    contentColor = Color.White,
                    focusedContainerColor = MaterialTheme.colorScheme.secondary,
                    focusedContentColor = MaterialTheme.colorScheme.onSecondary
                ),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(result.name ?: result.id ?: "", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        listOfNotNull(result.providerName, result.language, result.format).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
