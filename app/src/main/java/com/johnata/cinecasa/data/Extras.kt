package com.johnata.cinecasa.data

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Catálogo em memória, com índices prontos para busca e Top 10. */
object Catalog {
    @Volatile var movies: List<Item> = emptyList()
    @Volatile var series: List<Item> = emptyList()
    @Volatile var movieCats: List<Category> = emptyList()
    @Volatile var seriesCats: List<Category> = emptyList()
    @Volatile var movieById: Map<String, Item> = emptyMap()
    @Volatile var seriesById: Map<String, Item> = emptyMap()
    @Volatile var movieIndex: Matcher.Index = Matcher.Index(emptyList())
    @Volatile var seriesIndex: Matcher.Index = Matcher.Index(emptyList())
    @Volatile var movieSearch: List<Pair<String, Item>> = emptyList()
    @Volatile var seriesSearch: List<Pair<String, Item>> = emptyList()
    @Volatile var top10: Top10? = null

    private val serviceCache = ConcurrentHashMap<String, Pair<List<Item>, List<Item>>>()
    private val serviceKeyCache = ConcurrentHashMap<String, Set<String>>()

    private val serviceIndexCache = ConcurrentHashMap<String, Pair<Matcher.Index, Matcher.Index>>()

    /** Parte rápida: só troca as listas. Os índices pesados vêm depois, em buildIndexes(). */
    fun set(s: CatalogStore.Snapshot) {
        val mById = s.movies.associateBy { it.id }
        val sById = s.series.associateBy { it.id }
        serviceCache.clear()
        serviceKeyCache.clear()
        serviceIndexCache.clear()
        movieCats = s.movieCats
        seriesCats = s.seriesCats
        movies = s.movies
        series = s.series
        movieById = mById
        seriesById = sById
    }

    /** Parte pesada (busca e Top 10). Roda em segundo plano depois que a tela já apareceu. */
    fun buildIndexes() {
        val m = movies
        val s = series
        val mIdx = Matcher.Index(m)
        val sIdx = Matcher.Index(s)
        val mSearch = m.map { Matcher.searchKey(it.title) to it }
        val sSearch = s.map { Matcher.searchKey(it.title) to it }
        if (m !== movies || s !== series) return
        movieIndex = mIdx
        seriesIndex = sIdx
        movieSearch = mSearch
        seriesSearch = sSearch
    }

    fun serviceIndex(id: String): Pair<Matcher.Index, Matcher.Index> {
        serviceIndexCache[id]?.let { return it }
        val (m, s) = serviceItems(id)
        val r = Matcher.Index(m) to Matcher.Index(s)
        serviceIndexCache[id] = r
        return r
    }

    /** Filmes e séries das categorias de um streaming. */
    fun serviceItems(id: String): Pair<List<Item>, List<Item>> {
        serviceCache[id]?.let { return it }
        val mc = movieCats.filter { Streaming.match(it.name)?.id == id }.map { it.id }.toHashSet()
        val sc = seriesCats.filter { Streaming.match(it.name)?.id == id }.map { it.id }.toHashSet()
        val r = movies.filter { it.categoryId in mc } to series.filter { it.categoryId in sc }
        serviceCache[id] = r
        return r
    }

    fun serviceKeys(id: String): Set<String> {
        serviceKeyCache[id]?.let { return it }
        val (m, s) = serviceItems(id)
        val keys = HashSet<String>()
        m.forEach { keys.add(it.key()) }
        s.forEach { keys.add(it.key()) }
        serviceKeyCache[id] = keys
        return keys
    }
}

/** Guarda o catálogo na TV para o app abrir na hora, sem esperar o servidor. */
object CatalogStore {
    class Snapshot(
        val movies: List<Item>,
        val series: List<Item>,
        val movieCats: List<Category>,
        val seriesCats: List<Category>,
    ) {
        fun signature(): String =
            "${movies.size}:${series.size}:${movies.maxOfOrNull { it.added } ?: 0}:" +
                "${series.maxOfOrNull { it.added } ?: 0}:${movieCats.size}:${seriesCats.size}"
    }

    private const val HEADER = "#cinecasa-catalogo-v2"

    private fun file(c: Context) = File(c.filesDir, "catalogo.tsv")

    private fun clean(v: String?): String =
        v?.replace('\t', ' ')?.replace('\n', ' ')?.replace('\r', ' ') ?: ""

    private fun opt(v: String): String? = v.ifEmpty { null }

    /** Formato simples, uma linha por item: abre muito mais rápido que JSON. */
    fun load(c: Context): Snapshot? = try {
        File(c.filesDir, "catalogo.json").delete()
        val f = file(c)
        if (!f.exists()) {
            null
        } else {
            val movies = ArrayList<Item>()
            val series = ArrayList<Item>()
            val mc = ArrayList<Category>()
            val sc = ArrayList<Category>()
            var ok = false
            f.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (!ok) {
                        ok = line == HEADER
                        if (!ok) break
                        continue
                    }
                    val p = line.split('\t')
                    when (p[0]) {
                        "M", "S" -> if (p.size >= 8) {
                            val item = Item(
                                kind = if (p[0] == "M") Kind.MOVIE else Kind.SERIES,
                                id = p[1],
                                title = p[2],
                                poster = opt(p[3]),
                                categoryId = opt(p[4]),
                                ext = opt(p[5]),
                                rating = opt(p[6]),
                                added = p[7].toLongOrNull() ?: 0L,
                            )
                            if (p[0] == "M") movies.add(item) else series.add(item)
                        }
                        "MC" -> if (p.size >= 3) mc.add(Category(p[1], p[2]))
                        "SC" -> if (p.size >= 3) sc.add(Category(p[1], p[2]))
                    }
                }
            }
            if (ok) Snapshot(movies, series, mc, sc) else null
        }
    } catch (e: Exception) {
        null
    }

    fun save(c: Context, s: Snapshot) {
        try {
            val tmp = File(c.filesDir, "catalogo.tmp")
            tmp.bufferedWriter(Charsets.UTF_8).use { w ->
                w.write(HEADER)
                w.write("\n")
                fun item(tag: String, i: Item) {
                    w.write(tag); w.write("\t")
                    w.write(clean(i.id)); w.write("\t")
                    w.write(clean(i.title)); w.write("\t")
                    w.write(clean(i.poster)); w.write("\t")
                    w.write(clean(i.categoryId)); w.write("\t")
                    w.write(clean(i.ext)); w.write("\t")
                    w.write(clean(i.rating)); w.write("\t")
                    w.write(i.added.toString()); w.write("\n")
                }
                s.movies.forEach { item("M", it) }
                s.series.forEach { item("S", it) }
                s.movieCats.forEach { w.write("MC\t" + clean(it.id) + "\t" + clean(it.name) + "\n") }
                s.seriesCats.forEach { w.write("SC\t" + clean(it.id) + "\t" + clean(it.name) + "\n") }
            }
            if (!tmp.renameTo(file(c))) {
                tmp.copyTo(file(c), overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            // Sem espaço ou erro de escrita: o app continua funcionando sem o cache.
        }
    }
}

/** Uma lista de ranking: cada posição traz nomes alternativos do mesmo título. */
class RankList(val movies: List<List<String>>, val series: List<List<String>>)

class Top10(val updated: String, val trending: RankList, val services: Map<String, RankList>)

/** Top 10 publicado no GitHub do projeto e atualizado toda semana. */
object Top10Store {
    const val URL = "https://raw.githubusercontent.com/johnatacostajc-cyber/CineCasa/main/top10.json"

    private fun file(c: Context) = File(c.filesDir, "top10.json")

    fun cached(c: Context): Top10? = try {
        val f = file(c)
        if (f.exists()) parse(f.readText()) else null
    } catch (e: Exception) {
        null
    }

    suspend fun fetch(c: Context): Top10? = withContext(Dispatchers.IO) {
        try {
            val text = Xtream.httpGet(URL)
            val parsed = parse(text)
            if (parsed != null) file(c).writeText(text)
            parsed
        } catch (e: Exception) {
            null
        }
    }

    fun parse(text: String): Top10? = try {
        val o = JSONObject(text)
        val services = HashMap<String, RankList>()
        o.optJSONObject("services")?.let { s ->
            val keys = s.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                services[k] = ranks(s.optJSONObject(k))
            }
        }
        Top10(o.optString("updated"), ranks(o.optJSONObject("trending")), services)
    } catch (e: Exception) {
        null
    }

    private fun ranks(o: JSONObject?): RankList =
        RankList(entries(o?.optJSONArray("movies")), entries(o?.optJSONArray("series")))

    private fun entries(a: JSONArray?): List<List<String>> {
        if (a == null) return emptyList()
        val out = ArrayList<List<String>>()
        for (i in 0 until a.length()) {
            when (val e = a.opt(i)) {
                is JSONArray -> {
                    val alts = ArrayList<String>()
                    for (j in 0 until e.length()) e.optString(j).takeIf { it.isNotBlank() }?.let { alts.add(it) }
                    if (alts.isNotEmpty()) out.add(alts)
                }
                is String -> if (e.isNotBlank()) out.add(listOf(e))
            }
        }
        return out
    }
}

/** Acha na lista os títulos do ranking, mesmo com "(2024)", "Dublado", "4K" etc. no nome. */
object Matcher {
    private val junk = setOf(
        "dublado", "legendado", "dual", "audio", "4k", "hd", "fhd", "uhd", "sd", "cam",
        "nacional", "leg", "dub", "l", "d", "hdr", "1080p", "720p"
    )
    private val bracket = Regex("\\[[^\\]]*\\]|\\([^)]*\\)")
    private val year = Regex("^(19|20)\\d{2}$")

    fun searchKey(title: String): String = Streaming.norm(title).trim()

    fun key(title: String): String {
        val words = Streaming.norm(title.replace(bracket, " ")).trim()
            .split(' ').filter { it.isNotEmpty() && it !in junk }.toMutableList()
        while (words.size > 1 && year.matches(words.last())) words.removeAt(words.size - 1)
        return words.joinToString(" ")
    }

    class Index(items: List<Item>) {
        private val exact = HashMap<String, Item>()
        private val byFirstWord = HashMap<String, ArrayList<Pair<String, Item>>>()

        init {
            for (i in items) {
                val k = key(i.title)
                if (k.isEmpty()) continue
                if (!exact.containsKey(k)) exact[k] = i
                byFirstWord.getOrPut(k.substringBefore(' ')) { ArrayList() }.add(k to i)
            }
        }

        fun find(alts: List<String>): Item? {
            val expanded = LinkedHashSet<String>()
            for (a in alts) {
                expanded.add(a)
                if (a.contains(':')) expanded.add(a.substringBefore(':'))
            }
            val ks = expanded.map { key(it) }.filter { it.isNotEmpty() }
            for (k in ks) exact[k]?.let { return it }
            for (k in ks) {
                if (k.length < 4) continue
                val prefix = "$k "
                byFirstWord[k.substringBefore(' ')]?.firstOrNull { it.first.startsWith(prefix) }?.let { return it.second }
            }
            return null
        }
    }

    /** Monta o ranking com os títulos que existem na lista, na ordem do ranking. */
    fun rank(list: List<List<String>>, vararg indexes: Index, limit: Int = 10): List<Item> {
        val out = ArrayList<Item>()
        val seen = HashSet<String>()
        for (alts in list) {
            var found: Item? = null
            for (idx in indexes) {
                found = idx.find(alts)
                if (found != null) break
            }
            if (found != null && seen.add(found.key())) out.add(found)
            if (out.size >= limit) break
        }
        return out
    }
}

/** Sugestões: categorias que você mais assiste, títulos bem avaliados que você ainda não viu. */
object Recs {
    private fun rating(i: Item) = i.rating?.toDoubleOrNull() ?: 0.0

    fun forUser(c: Context, pool: List<Item>, limit: Int = 30): List<Item> {
        val catCount = HashMap<String, Int>()
        val seen = HashSet<String>()
        for (e in History.list(c)) {
            val src = when (e.item.kind) {
                Kind.EPISODE -> e.item.seriesId?.let { Catalog.seriesById[it] }
                Kind.MOVIE -> Catalog.movieById[e.item.id]
                else -> null
            } ?: continue
            seen.add(src.key())
            src.categoryId?.let { catCount[it] = (catCount[it] ?: 0) + 1 }
        }
        val candidates = pool.filter { it.key() !in seen }
        val sorted = if (catCount.isEmpty()) {
            candidates.sortedByDescending { rating(it) }
        } else {
            candidates.sortedWith(
                compareByDescending<Item> { catCount[it.categoryId] ?: 0 }.thenByDescending { rating(it) }
            )
        }
        return sorted.take(limit)
    }
}
