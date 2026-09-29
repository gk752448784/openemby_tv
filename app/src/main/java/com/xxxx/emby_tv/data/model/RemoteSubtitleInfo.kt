package com.xxxx.emby_tv.data.model

import com.google.gson.annotations.SerializedName

data class RemoteSubtitleInfo(
    @SerializedName("Id") val id: String? = null,
    @SerializedName("Name") val name: String? = null,
    @SerializedName("ProviderName") val providerName: String? = null,
    @SerializedName("Language") val language: String? = null,
    @SerializedName("Format") val format: String? = null,
    @SerializedName("IsHashMatch") val isHashMatch: Boolean = false
)

data class SubtitleDownloadResult(
    @SerializedName("NewIndex") val newIndex: Int? = null
)
