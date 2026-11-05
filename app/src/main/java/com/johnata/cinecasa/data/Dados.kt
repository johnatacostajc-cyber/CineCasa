package com.johnata.cinecasa.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import java.io.IOException
import java.io.Serializable
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class Kind { MOVIE, SERIES, EPISODE, ACTION, SERVICE }

data class Category(val id: String, val name: String)

/** Qualquer coisa que aparece como um "card" na tela: filme, série, episódio ou ação. */
data class Item(
    val kind: Kind,
    val id: String,
    val title: String,
    val poster: String? = null,
    val categoryId: String? = null,
    val ext: String? = null,
    val rating: String? = null,
    val subtitle: String? = null,
    val seriesId: String? = null,
    val added: Long = 0L,
    /** Só para exibição na fileira "Continuar assistindo" (0–100). -1 = não mostrar. */
    val progress: Int = -1,
) : Serializable {

    fun key(): String = "${kind.name}:$id"

    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind.name)
        put("id", id)
        put("title", title)
        putOpt("poster", poster)
        putOpt("ext", ext)
        putOpt("rating", rating)
        putOpt("subtitle", subtitle)
        putOpt("seriesId", seriesId)
    }

    companion object {
        fun fromJson(o: JSONObject): Item? = try {
            Item(
                kind = Kind.valueOf(o.getString("kind")),
                id = o.getString("id"),
                title = o.getString("title"),
                poster = o.str("poster"),
                ext = o.str("ext"),
                rating = o.str("rating"),
                subtitle = o.str("subtitle"),
                seriesId = o.str("seriesId"),
            )
        } catch (e: Exception) {
            null
        }
    }
}

data class MovieInfo(
    val plot: String?,
    val genre: String?,
    val year: String?,
    val duration: String?,
    val rating: String?,
    val poster: String?,
    val backdrop: String?,
)

data class SeriesInfo(
    val name: String,
    val plot: String?,
    val seasons: List<Pair<String, List<Item>>>,
)

/** Lê um texto do JSON tratando null/vazio como ausente. */
fun JSONObject.str(key: String): String? {
    if (isNull(key)) return null
    val v = optString(key, "").trim()
    return if (v.isEmpty() || v == "null") null else v
}

data class Account(val server: String, val user: String, val pass: String) {
    companion object {
        /**
         * Monta a conta a partir do que a pessoa digitou. Aceita só o endereço do servidor
         * ou um link completo do tipo http://srv:8080/get.php?username=X&password=Y.
         */
        fun build(serverIn: String, userIn: String, passIn: String): Account? {
            var s = serverIn.trim()
            var u = userIn.trim()
            var p = passIn.trim()
            if (s.isEmpty()) return null
            if (!s.contains("://")) s = "http://$s"
            val uri = Uri.parse(s)
            if (u.isEmpty()) uri.getQueryParameter("username")?.let { u = it }
            if (p.isEmpty()) uri.getQueryParameter("password")?.let { p = it }
            val host = uri.host ?: return null
            val scheme = uri.scheme ?: "http"
            val port = if (uri.port != -1) ":${uri.port}" else ""
            if (u.isEmpty() || p.isEmpty()) return null
            return Account("$scheme://$host$port", u, p)
        }
    }
}

object Prefs {
    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences("cinecasa", Context.MODE_PRIVATE)

    fun account(c: Context): Account? {
        val s = sp(c)
        val server = s.getString("server", null) ?: return null
        val user = s.getString("user", null) ?: return null
        val pass = s.getString("pass", null) ?: return null
        return Account(server, user, pass)
    }

    fun save(c: Context, a: Account) {
        sp(c).edit()
            .putString("server", a.server)
            .putString("user", a.user)
            .putString("pass", a.pass)
            .apply()
    }

    fun logout(c: Context) {
        sp(c).edit().clear().apply()
    }

    internal fun historyRaw(c: Context): String? = sp(c).getString("history", null)
    internal fun setHistoryRaw(c: Context, v: String) {
        sp(c).edit().putString("history", v).apply()
    }
}

/** "Continuar assistindo": guarda onde parou em cada filme/episódio. */
object History {
    data class Entry(val item: Item, val pos: Long, val dur: Long)

    fun list(c: Context): List<Entry> {
        val raw = Prefs.historyRaw(c) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            val out = ArrayList<Entry>()
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val itemJson = o.optJSONObject("item") ?: continue
                val item = Item.fromJson(itemJson) ?: continue
                out.add(Entry(item, o.optLong("pos"), o.optLong("dur")))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun position(c: Context, item: Item): Long =
        list(c).firstOrNull { it.item.key() == item.key() }?.pos ?: 0L

    fun save(c: Context, item: Item, pos: Long, dur: Long) {
        val l = list(c).filterNot { it.item.key() == item.key() }.toMutableList()
        val finished = dur > 0 && pos > dur * 0.95
        if (!finished && pos > 15_000) l.add(0, Entry(item.copy(progress = -1), pos, dur))
        write(c, l.take(30))
    }

    fun remove(c: Context, item: Item) {
        write(c, list(c).filterNot { it.item.key() == item.key() })
    }

    private fun write(c: Context, l: List<Entry>) {
        val a = JSONArray()
        for (e in l) {
            a.put(JSONObject().apply {
                put("item", e.item.toJson())
                put("pos", e.pos)
                put("dur", e.dur)
            })
        }
        Prefs.setHistoryRaw(c, a.toString())
    }
}

/** Cliente da API Xtream Codes (player_api.php), usada pela maioria das listas IPTV com login e senha. */
object Xtream {
    const val UA = "Mozilla/5.0 (Linux; Android 11; Android TV) CineCasa/1.0"

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun httpGet(start: String): String {
        var url = start
        repeat(6) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", UA)
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: throw IOException("Redirecionamento inválido")
                    url = URL(URL(url), loc).toString()
                    return@repeat
                }
                if (code !in 200..299) throw IOException("O servidor respondeu com erro $code")
                return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Redirecionamentos demais")
    }

    private fun api(acc: Account, action: String?, extra: String = ""): String {
        val sb = StringBuilder(acc.server)
            .append("/player_api.php?username=").append(enc(acc.user))
            .append("&password=").append(enc(acc.pass))
        if (action != null) sb.append("&action=").append(action)
        sb.append(extra)
        return httpGet(sb.toString())
    }

    private fun objects(text: String): List<JSONObject> {
        val t = text.trim()
        if (!t.startsWith("[")) return emptyList()
        val a = JSONArray(t)
        val out = ArrayList<JSONObject>(a.length())
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { out.add(it) }
        return out
    }

    private fun rating(o: JSONObject): String? {
        val r = o.str("rating") ?: return null
        val d = r.replace(',', '.').toDoubleOrNull() ?: return null
        if (d <= 0.0) return null
        return String.format(java.util.Locale.US, "%.1f", d)
    }

    suspend fun login(acc: Account): Boolean = withContext(Dispatchers.IO) {
        val text = api(acc, null).trim()
        if (!text.startsWith("{")) return@withContext false
        val info = JSONObject(text).optJSONObject("user_info") ?: return@withContext false
        info.optString("auth") == "1"
    }

    suspend fun movieCategories(acc: Account): List<Category> = withContext(Dispatchers.IO) {
        objects(api(acc, "get_vod_categories")).map {
            Category(it.str("category_id") ?: "", it.str("category_name") ?: "Sem nome")
        }
    }

    suspend fun movies(acc: Account): List<Item> = withContext(Dispatchers.IO) {
        objects(api(acc, "get_vod_streams")).mapNotNull { o ->
            val id = o.str("stream_id") ?: return@mapNotNull null
            Item(
                kind = Kind.MOVIE,
                id = id,
                title = o.str("name") ?: "Sem título",
                poster = o.str("stream_icon"),
                categoryId = o.str("category_id"),
                ext = o.str("container_extension") ?: "mp4",
                rating = rating(o),
                added = o.optLong("added", 0L),
            )
        }
    }

    suspend fun seriesCategories(acc: Account): List<Category> = withContext(Dispatchers.IO) {
        objects(api(acc, "get_series_categories")).map {
            Category(it.str("category_id") ?: "", it.str("category_name") ?: "Sem nome")
        }
    }

    suspend fun series(acc: Account): List<Item> = withContext(Dispatchers.IO) {
        objects(api(acc, "get_series")).mapNotNull { o ->
            val id = o.str("series_id") ?: return@mapNotNull null
            Item(
                kind = Kind.SERIES,
                id = id,
                title = o.str("name") ?: "Sem título",
                poster = o.str("cover"),
                categoryId = o.str("category_id"),
                rating = rating(o),
                added = o.optLong("last_modified", 0L),
            )
        }
    }

    suspend fun movieInfo(acc: Account, id: String): MovieInfo? = withContext(Dispatchers.IO) {
        try {
            val text = api(acc, "get_vod_info", "&vod_id=" + enc(id)).trim()
            if (!text.startsWith("{")) return@withContext null
            val info = JSONObject(text).optJSONObject("info") ?: return@withContext null
            val backdrop = when (val b = info.opt("backdrop_path")) {
                is JSONArray -> if (b.length() > 0) b.optString(0).ifBlank { null } else null
                is String -> b.ifBlank { null }
                else -> null
            }
            MovieInfo(
                plot = info.str("plot") ?: info.str("description"),
                genre = info.str("genre"),
                year = (info.str("releasedate") ?: info.str("release_date"))?.take(4),
                duration = info.str("duration"),
                rating = rating(info),
                poster = info.str("movie_image") ?: info.str("cover_big"),
                backdrop = backdrop,
            )
        } catch (e: Exception) {
            null
        }
    }

    suspend fun seriesInfo(acc: Account, series: Item): SeriesInfo = withContext(Dispatchers.IO) {
        val text = api(acc, "get_series_info", "&series_id=" + enc(series.id)).trim()
        val root = JSONObject(text)
        val info = root.optJSONObject("info")
        val name = info?.str("name") ?: series.title
        val cover = info?.str("cover") ?: series.poster
        val seasons = LinkedHashMap<String, MutableList<Item>>()

        fun addEpisode(e: JSONObject, seasonKey: String?) {
            val id = e.str("id") ?: return
            val season = e.str("season") ?: seasonKey ?: "1"
            val num = e.str("episode_num") ?: ""
            val epInfo = e.optJSONObject("info")
            val label = if (num.isNotEmpty()) "T${season} · E$num" else "Temporada $season"
            seasons.getOrPut(season) { ArrayList() }.add(
                Item(
                    kind = Kind.EPISODE,
                    id = id,
                    title = e.str("title") ?: "Episódio $num",
                    poster = epInfo?.str("movie_image") ?: cover,
                    ext = e.str("container_extension") ?: "mp4",
                    subtitle = "$name · $label",
                    seriesId = series.id,
                )
            )
        }

        when (val eps = root.opt("episodes")) {
            is JSONObject -> {
                val keys = eps.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val arr = eps.optJSONArray(k) ?: continue
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { addEpisode(it, k) }
                }
            }
            is JSONArray -> {
                for (i in 0 until eps.length()) {
                    when (val el = eps.opt(i)) {
                        is JSONArray -> for (j in 0 until el.length()) el.optJSONObject(j)?.let { addEpisode(it, null) }
                        is JSONObject -> addEpisode(el, null)
                    }
                }
            }
        }

        val ordered = seasons.entries
            .sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }
            .map { (k, v) -> "Temporada $k" to v.sortedBy { ep -> ep.subtitle?.substringAfterLast("E")?.toIntOrNull() ?: 0 } }
        SeriesInfo(name, info?.str("plot"), ordered)
    }

    fun streamUrl(acc: Account, item: Item): String = when (item.kind) {
        Kind.MOVIE -> "${acc.server}/movie/${acc.user}/${acc.pass}/${item.id}.${item.ext ?: "mp4"}"
        Kind.EPISODE -> "${acc.server}/series/${acc.user}/${acc.pass}/${item.id}.${item.ext ?: "mp4"}"
        else -> ""
    }
}

/** Catálogo carregado na tela inicial, reutilizado pela busca. */
object Catalog {
    @Volatile var movies: List<Item> = emptyList()
    @Volatile var series: List<Item> = emptyList()
    @Volatile var movieCats: List<Category> = emptyList()
    @Volatile var seriesCats: List<Category> = emptyList()
}

data class Service(val id: String, val name: String, val keywords: List<String>)

/** Reconhece categorias de streaming pelo nome (ex.: "FILMES | NETFLIX", "Séries Disney+"). */
object Streaming {
    val all = listOf(
        Service("netflix", "Netflix", listOf("netflix")),
        Service("disney", "Disney+", listOf("disney", "star plus")),
        Service("prime", "Prime Video", listOf("prime video", "amazon", "prime")),
        Service("max", "Max", listOf("hbo", "max")),
        Service("paramount", "Paramount+", listOf("paramount")),
        Service("globoplay", "Globoplay", listOf("globoplay", "globo play")),
        Service("apple", "Apple TV+", listOf("apple tv", "apple")),
        Service("crunchyroll", "Crunchyroll", listOf("crunchyroll")),
        Service("discovery", "Discovery+", listOf("discovery")),
    )

    /** Minúsculas, sem acento, "+" vira " plus ", só letras e números, com espaço nas pontas. */
    fun norm(s: String): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .replace("+", " plus ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
        return " $n "
    }

    fun match(categoryName: String): Service? {
        val n = norm(categoryName)
        return all.firstOrNull { s -> s.keywords.any { k -> n.contains(" $k ") } }
    }

    /** Streamings que realmente têm algum filme ou série na lista. */
    fun present(
        movieCats: List<Category>, movies: List<Item>,
        seriesCats: List<Category>, series: List<Item>,
    ): List<Service> {
        val used = HashSet<String>()
        (movies + series).forEach { it.categoryId?.let { id -> used.add(id) } }
        val found = HashSet<String>()
        for (c in movieCats + seriesCats) {
            if (c.id !in used) continue
            match(c.name)?.let { found.add(it.id) }
        }
        return all.filter { it.id in found }
    }
}

/** Deixa os nomes de categoria mais limpos: "FILMES | AÇÃO" vira "Ação". */
object Names {
    private val typeWords = setOf("filmes", "filme", "films", "film", "series", "serie", "vod", "movies", "movie")
    private val small = setOf("de", "da", "do", "das", "dos", "e", "em", "a", "o", "com", "para", "no", "na", "nos", "nas")
    private val upper = setOf(
        "4k", "hd", "fhd", "uhd", "sd", "tv", "hbo", "dc", "3d", "eua", "ufc", "mma", "dvd",
        "br", "bbc", "mtv", "nba", "nfl", "ova", "lgbt", "lgbtqia"
    )
    private val lead = Regex("^[^\\p{L}\\p{N}]+")
    private val head = Regex("^([\\p{L}\\p{N}]+)(\\s*[|:•/–—-]+\\s*|\\s+)(.*)$")

    private fun plain(w: String): String =
        Normalizer.normalize(w, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .filter { it.isLetterOrDigit() }

    fun clean(raw: String): String {
        var s = raw.trim().replace(lead, "")
        var guard = 0
        while (guard++ < 4) {
            val m = head.find(s) ?: break
            val word = plain(m.groupValues[1])
            val sep = m.groupValues[2]
            val rest = m.groupValues[3]
            if (word !in typeWords || rest.isBlank()) break
            val next = plain(rest.trim().substringBefore(' '))
            if (sep.isBlank() && next in small) break
            s = rest.replace(lead, "")
        }
        s = s.trim().trimEnd('|', '-', ':', '•', '/', ' ')
        if (s.isBlank()) s = raw.trim()
        if (s.none { it.isLowerCase() }) s = titleCase(s)
        return s
    }

    private fun titleCase(s: String): String =
        s.lowercase().split(' ').mapIndexed { i, w ->
            val p = plain(w)
            when {
                p in upper -> w.uppercase()
                i > 0 && p in small -> w
                else -> {
                    val idx = w.indexOfFirst { it.isLetter() }
                    if (idx < 0) w else w.substring(0, idx) + w[idx].uppercaseChar() + w.substring(idx + 1)
                }
            }
        }.joinToString(" ")
}
