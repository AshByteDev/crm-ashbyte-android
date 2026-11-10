package ru.ashcrm.app.data

import java.net.URI

/** The address of a portal: a scheme and a host (docs/ANDROID_CONTRACT.md §1). */
object Portal {

    /** Normalises what a person typed: `crm.company.ru` → `https://crm.company.ru`; null — it is not an address. */
    fun normalize(input: String): String? {
        var s = input.trim().trimEnd('/')
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "https://$s"
        val uri = runCatching { URI(s) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        if (!uri.path.isNullOrEmpty() && uri.path != "/" || uri.query != null || uri.fragment != null || uri.userInfo != null) return null
        if (scheme == "http" && !isLocal(host)) return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "$scheme://$host$port"
    }

    /** `http://` is only for this device and the local network: keys would go over the internet in clear text. */
    fun isLocal(host: String): Boolean {
        if (host == "localhost") return true
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { it.toIntOrNull() !in 0..255 }) return false
        val (a, b) = parts[0].toInt() to parts[1].toInt()
        return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }

    /** Only a path of the portal is opened: one leading slash, no scheme or host (§5.1). */
    /** The portal lives in a local network (by its address or what its name resolves to): Android 17 asks a permission. */
    suspend fun needsLocalNetwork(portal: String): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val host = runCatching { URI(portal).host }.getOrNull() ?: return@withContext false
        if (isLocal(host)) return@withContext true
        runCatching { java.net.InetAddress.getAllByName(host) }.getOrNull().orEmpty()
            .any { it.isSiteLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress }
    }

    fun safePath(path: String?): String? =
        path?.takeIf { it.startsWith("/") && !it.startsWith("//") && !it.contains('\\') }

    fun sameOrigin(portal: String, url: String): Boolean {
        val a = runCatching { URI(portal) }.getOrNull() ?: return false
        val b = runCatching { URI(url) }.getOrNull() ?: return false
        fun port(u: URI) = if (u.port != -1) u.port else if (u.scheme == "https") 443 else 80
        return a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && port(a) == port(b)
    }

}
