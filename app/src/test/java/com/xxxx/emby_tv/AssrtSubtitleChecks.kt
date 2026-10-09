package com.xxxx.emby_tv

import com.xxxx.emby_tv.data.remote.AssrtSubtitles

fun main() {
    var checks = 0
    fun expect(expected: Any?, actual: Any?) { check(expected == actual) { "Expected $expected, got $actual" }; checks++ }
    val search = AssrtSubtitles.parseSearch("""{"status":0,"sub":{"subs":[{"id":123,"native_name":"Test S01","lang":{"desc":"简体"},"subtype":"SRT"}]}}""")
    expect(1, search.size)
    expect("123", search.single().id)
    expect("Test S01", search.single().name)
    expect("简体", search.single().language)
    expect(0, AssrtSubtitles.parseSearch("""{"status":0,"sub":{"subs":[]}}""").size)
    expect(true, runCatching { AssrtSubtitles.parseSearch("""{"status":20001}""") }.isFailure)
    expect(true, runCatching { AssrtSubtitles.parseSearch("{}") }.isFailure)
    val files = AssrtSubtitles.parseFiles("""{"status":0,"sub":{"subs":[{"filename":"pack.zip","url":"https://file.assrt.net/pack.zip","filelist":[{"f":"S01E01.srt","url":"https://file.assrt.net/1"},{"f":"S01E02.ass","url":"https://file.assrt.net/2"},{"f":"cover.jpg","url":"https://file.assrt.net/3"}]}]}}""")
    expect(listOf("S01E01.srt", "S01E02.ass"), files.map { it.name })
    expect("ssa", AssrtSubtitles.codec("A.SSA"))
    expect(null, AssrtSubtitles.codec("A.zip"))
    val srt = "1\n00:00:01,000 --> 00:00:02,000\n中文字幕\n"
    expect(srt, AssrtSubtitles.normalize(srt.toByteArray(Charsets.UTF_8), "srt").toString(Charsets.UTF_8))
    expect(srt, AssrtSubtitles.normalize(srt.toByteArray(charset("GB18030")), "srt").toString(Charsets.UTF_8))
    expect(true, runCatching { AssrtSubtitles.normalize("<html>error</html>".toByteArray(), "srt") }.isFailure)
    expect(true, runCatching { AssrtSubtitles.normalize(byteArrayOf(80,75,3,4), "srt") }.isFailure)
    expect(true, runCatching { AssrtSubtitles.normalize(ByteArray(AssrtSubtitles.MAX_BYTES + 1), "srt") }.isFailure)
    println("PASS: $checks ASSRT subtitle checks")
}
