package com.local.tasknotescompanion.domain

import java.net.URI

data class ReminderWebLink(val start: Int, val end: Int, val url: String)

private val webAddress = Regex(
    """(?<![\w@/:.])(?:https?://[^\s<>"'`“”‘’]+|(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\?\.)+[a-z]{2,63}(?::\d{1,5})?(?:[/?#][^\s<>"'`“”‘’]*)?)""",
    RegexOption.IGNORE_CASE,
)

fun reminderWebLinks(text: String): List<ReminderWebLink> = webAddress.findAll(text).mapNotNull { match ->
    var address = match.value.trimEnd('.', ',', ';', ':', '!', '?')
    for ((close, open) in listOf(')' to '(', ']' to '[', '}' to '{')) {
        while (address.endsWith(close) && address.count { it == close } > address.count { it == open }) {
            address = address.dropLast(1).trimEnd('.', ',', ';', ':', '!', '?')
        }
    }
    val normalized = address.replace("\\.", ".")
    val url = if (normalized.startsWith("http://", true) || normalized.startsWith("https://", true)) normalized else "https://$normalized"
    val uri = runCatching { URI(url) }.getOrNull()
    if (uri?.host.isNullOrBlank() || uri?.userInfo != null) null
    else ReminderWebLink(match.range.first, match.range.first + address.length, url)
}.toList()
