package com.johnata.cinecasa.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.DividerRow
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.SectionRow
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.johnata.cinecasa.R
import com.johnata.cinecasa.data.Account
import com.johnata.cinecasa.data.Catalog
import com.johnata.cinecasa.data.CatalogStore
import com.johnata.cinecasa.data.Category
import com.johnata.cinecasa.data.History
import com.johnata.cinecasa.data.Item
import com.johnata.cinecasa.data.Kind
import com.johnata.cinecasa.data.Matcher
import com.johnata.cinecasa.data.Prefs
import com.johnata.cinecasa.data.Recs
import com.johnata.cinecasa.data.Service
import com.johnata.cinecasa.data.Streaming
import com.johnata.cinecasa.data.Top10Store
import com.johnata.cinecasa.data.Xtream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val EXTRA_SERVICE = "service"
const val EXTRA_SERVICE_NAME = "service_name"

/** Item com posição no ranking (para as fileiras de Top 10). */
class Ranked(val item: Item, val rank: Int)

/** Converte o que foi clicado (card normal ou do Top 10) no item. */
fun asItem(o: Any?): Item? = when (o) {
    is Item -> o
    is Ranked -> o.item
    else -> null
}

fun rankedRow(id: Long, name: String, items: List<Item>): ListRow {
    val a = ArrayObjectAdapter(RankedPresenter())
    a.addAll(0, items.mapIndexed { i, it -> Ranked(it, i + 1) })
    return ListRow(HeaderItem(id, name), a)
}

/** Card do Top 10: número grande encostado na capa. */
class RankedPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.BOTTOM
        box.isFocusable = true
        box.isFocusableInTouchMode = true
        box.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            (216 * d).toInt()
        )

        val num = TextView(ctx)
        val nlp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        nlp.rightMargin = (-14 * d).toInt()
        num.layoutParams = nlp
        num.textSize = 96f
        num.typeface = Typeface.DEFAULT_BOLD
        num.setTextColor(0xFFE6E6EB.toInt())
        num.includeFontPadding = false
        num.setShadowLayer(12f, 0f, 0f, 0xCC000000.toInt())
        box.addView(num)

        val poster = ImageView(ctx)
        poster.layoutParams = LinearLayout.LayoutParams((140 * d).toInt(), (210 * d).toInt())
        poster.scaleType = ImageView.ScaleType.CENTER_CROP
        val pad = (3 * d).toInt()
        poster.setPadding(pad, pad, pad, pad)
        val frame = StateListDrawable()
        frame.addState(intArrayOf(android.R.attr.state_focused), ColorDrawable(Color.WHITE))
        frame.addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        poster.background = frame
        poster.isDuplicateParentStateEnabled = true
        box.addView(poster)
        return ViewHolder(box)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val r = item as? Ranked ?: return
        val box = viewHolder.view as LinearLayout
        (box.getChildAt(0) as TextView).text = r.rank.toString()
        val poster = box.getChildAt(1) as ImageView
        poster.contentDescription = r.item.title
        Glide.with(box.context)
            .load(r.item.poster)
            .centerCrop()
            .placeholder(placeholder())
            .error(placeholder())
            .into(poster)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val box = viewHolder.view as LinearLayout
        Glide.with(box.context).clear(box.getChildAt(1) as ImageView)
    }
}

/** Tela inicial: abre na hora com o catálogo salvo e atualiza em segundo plano. */
class MainFragment : BrowseSupportFragment() {

    private class Home(
        val services: List<Service>,
        val recent: List<Item>,
        val movieShelves: List<Shelf>,
        val seriesShelves: List<Shelf>,
    )

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private val posters = CardPresenter()
    private var continueRow: ListRow? = null
    private val trendRows = ArrayList<ListRow>()
    private var shownSig: String? = null
    private var indexesReady = false
    private var accountAdapter: ArrayObjectAdapter? = null
    private var timeCache: Long = -1
    private var timeServer: Long = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        // Menu da esquerda começa escondido e só abre com a seta para a esquerda.
        headersState = HEADERS_HIDDEN
        isHeadersTransitionOnBackEnabled = true
        brandColor = ContextCompat.getColor(requireContext(), R.color.brand)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.accent)
        adapter = rowsAdapter

        setOnSearchClickedListener {
            startActivity(Intent(requireContext(), SearchActivity::class.java))
        }
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            asItem(item)?.let { Nav.open(requireActivity(), it) }
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        refreshContinue()
    }

    private fun load() {
        val ctx = requireContext().applicationContext
        val acc = Prefs.account(ctx) ?: return
        refreshContinue()
        lifecycleScope.launch {
            val cachedTop = withContext(Dispatchers.IO) { Top10Store.cached(ctx) }
            if (cachedTop != null) Catalog.top10 = cachedTop
            launch {
                val freshTop = Top10Store.fetch(ctx)
                if (freshTop != null) {
                    Catalog.top10 = freshTop
                    showTrending()
                }
            }

            val start = SystemClock.elapsedRealtime()
            val cached = withContext(Dispatchers.IO) { CatalogStore.load(ctx) }
            if (cached != null && (cached.movies.isNotEmpty() || cached.series.isNotEmpty())) {
                show(cached)
                timeCache = SystemClock.elapsedRealtime() - start
                updateDiag()
            } else {
                Toast.makeText(ctx, "Carregando catálogo pela primeira vez…", Toast.LENGTH_LONG).show()
            }

            val netStart = SystemClock.elapsedRealtime()
            val fresh = fetchAll(acc)
            timeServer = SystemClock.elapsedRealtime() - netStart
            if (fresh != null) {
                withContext(Dispatchers.IO) { CatalogStore.save(ctx, fresh) }
                if (shownSig == null) {
                    show(fresh)
                } else if (fresh.signature() != shownSig) {
                    // Já tem catálogo na tela: atualiza por trás, sem recarregar a tela.
                    // As novidades aparecem nas fileiras na próxima vez que o app abrir.
                    withContext(Dispatchers.Default) {
                        Catalog.set(fresh)
                        Catalog.buildIndexes()
                    }
                    shownSig = fresh.signature()
                    showTrending()
                }
                updateDiag()
            } else if (shownSig == null) {
                Toast.makeText(
                    ctx,
                    "Não encontrei filmes nem séries nessa lista. Confira a internet ou os dados de acesso.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Baixa as 4 listas ao mesmo tempo. Se alguma falhar, mantém a versão salva dela. */
    private suspend fun fetchAll(acc: Account): CatalogStore.Snapshot? = coroutineScope {
        val mc = async { runCatching { Xtream.movieCategories(acc) }.getOrNull() }
        val m = async { runCatching { Xtream.movies(acc) }.getOrNull() }
        val sc = async { runCatching { Xtream.seriesCategories(acc) }.getOrNull() }
        val s = async { runCatching { Xtream.series(acc) }.getOrNull() }
        val movies = m.await()
        val series = s.await()
        if (movies.isNullOrEmpty() && series.isNullOrEmpty()) {
            null
        } else {
            CatalogStore.Snapshot(
                movies ?: Catalog.movies,
                series ?: Catalog.series,
                mc.await() ?: Catalog.movieCats,
                sc.await() ?: Catalog.seriesCats,
            )
        }
    }

    private suspend fun show(snap: CatalogStore.Snapshot) {
        indexesReady = false
        val home = withContext(Dispatchers.Default) {
            Catalog.set(snap)
            val notService = { c: Category -> Streaming.match(c.name) == null }
            Home(
                Streaming.present(snap.movieCats, snap.movies, snap.seriesCats, snap.series),
                snap.movies.filter { it.added > 0 }.sortedByDescending { it.added }.take(40),
                shelves(snap.movieCats, snap.movies, notService, withOthers = true),
                shelves(snap.seriesCats, snap.series, notService, withOthers = true),
            )
        }
        if (!isAdded) return

        rowsAdapter.clear()
        continueRow = null
        trendRows.clear()
        refreshContinue()

        var headerId = 10L
        if (home.services.isNotEmpty()) {
            val a = ArrayObjectAdapter(ServicePresenter())
            a.addAll(0, home.services.map { Item(Kind.SERVICE, it.id, it.name) })
            rowsAdapter.add(ListRow(HeaderItem(headerId++, "Streamings"), a))
        }
        if (home.recent.isNotEmpty()) addRow(headerId++, "Adicionados recentemente", home.recent)
        if (home.movieShelves.isNotEmpty()) {
            rowsAdapter.add(SectionRow(HeaderItem(headerId++, "Filmes")))
            for (s in home.movieShelves) addRow(headerId++, s.title, s.items)
        }
        if (home.seriesShelves.isNotEmpty()) {
            rowsAdapter.add(SectionRow(HeaderItem(headerId++, "Séries")))
            for (s in home.seriesShelves) addRow(headerId++, s.title, s.items)
        }
        rowsAdapter.add(DividerRow())
        val acct = ArrayObjectAdapter(posters)
        acct.add(Item(Kind.ACTION, "logout", "Trocar conta / sair"))
        acct.add(diagItem())
        accountAdapter = acct
        rowsAdapter.add(ListRow(HeaderItem(headerId, "Conta"), acct))

        shownSig = snap.signature()
        // Índices de busca e Top 10 são montados depois que a tela já apareceu.
        lifecycleScope.launch {
            withContext(Dispatchers.Default) { Catalog.buildIndexes() }
            indexesReady = true
            showTrending()
        }
    }

    private fun secs(ms: Long) = String.format(java.util.Locale("pt", "BR"), "%.1fs", ms / 1000.0)

    private fun diagItem(): Item {
        val parts = ArrayList<String>()
        if (timeCache >= 0) parts.add("abriu em ${secs(timeCache)}")
        if (timeServer >= 0) parts.add("servidor ${secs(timeServer)}")
        parts.add("${Catalog.movies.size} filmes, ${Catalog.series.size} séries")
        return Item(Kind.ACTION, "diag", "Desempenho", subtitle = parts.joinToString(" · "))
    }

    private fun updateDiag() {
        val a = accountAdapter ?: return
        if (a.size() >= 2) a.replace(1, diagItem())
    }

    /** Fileiras "Top 10 da semana" logo abaixo de "Continuar assistindo". */
    private fun showTrending() {
        val t = Catalog.top10 ?: return
        if (shownSig == null || !indexesReady) return
        lifecycleScope.launch {
            val ranked = withContext(Dispatchers.Default) {
                Matcher.rank(t.trending.movies, Catalog.movieIndex) to
                    Matcher.rank(t.trending.series, Catalog.seriesIndex)
            }
            if (!isAdded) return@launch
            for (r in trendRows) rowsAdapter.remove(r)
            trendRows.clear()
            var pos = if (continueRow != null) 1 else 0
            if (ranked.first.isNotEmpty()) {
                val r = rankedRow(2L, "Top 10 filmes da semana", ranked.first)
                rowsAdapter.add(pos++, r)
                trendRows.add(r)
            }
            if (ranked.second.isNotEmpty()) {
                val r = rankedRow(3L, "Top 10 séries da semana", ranked.second)
                rowsAdapter.add(pos, r)
                trendRows.add(r)
            }
        }
    }

    private fun addRow(id: Long, name: String, items: List<Item>) {
        val a = ArrayObjectAdapter(posters)
        a.addAll(0, items)
        rowsAdapter.add(ListRow(HeaderItem(id, name), a))
    }

    private fun refreshContinue() {
        if (!isAdded) return
        val entries = History.list(requireContext())
        val items = entries.map { e ->
            val pct = if (e.dur > 0) ((e.pos * 100) / e.dur).toInt().coerceIn(0, 99) else 0
            e.item.copy(progress = pct)
        }
        val row = continueRow
        if (items.isEmpty()) {
            if (row != null) {
                rowsAdapter.remove(row)
                continueRow = null
            }
            return
        }
        if (row == null) {
            val newRow = ListRow(HeaderItem(1L, "Continuar assistindo"), ArrayObjectAdapter(posters))
            rowsAdapter.add(0, newRow)
            continueRow = newRow
        }
        val a = continueRow!!.adapter as ArrayObjectAdapter
        a.clear()
        a.addAll(0, items)
    }
}

/** Tela de um streaming: destaques, Top 10, sugestões, categorias e busca própria. */
class ServiceFragment : BrowseSupportFragment() {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val svc = requireActivity().intent.getItem()
        if (svc == null) {
            requireActivity().finish()
            return
        }
        title = svc.title
        headersState = HEADERS_HIDDEN
        isHeadersTransitionOnBackEnabled = true
        brandColor = ContextCompat.getColor(requireContext(), R.color.brand)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.accent)
        adapter = rowsAdapter
        setOnSearchClickedListener {
            startActivity(
                Intent(requireContext(), SearchActivity::class.java)
                    .putExtra(EXTRA_SERVICE, svc.id)
                    .putExtra(EXTRA_SERVICE_NAME, svc.title)
            )
        }
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            asItem(item)?.let { Nav.open(requireActivity(), it) }
        }

        val ctx = requireContext().applicationContext
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.Default) { servicePage(ctx, svc.id) }
            if (!isAdded) return@launch
            if (rows.isEmpty()) {
                Toast.makeText(ctx, "Nada encontrado. Volte à tela inicial e tente de novo.", Toast.LENGTH_LONG).show()
            }
            for (r in rows) rowsAdapter.add(r)
        }
    }
}

private fun rating(i: Item) = i.rating?.toDoubleOrNull() ?: 0.0

private fun bestRated(list: List<Item>): List<Item> =
    list.filter { rating(it) > 0 }.sortedByDescending { rating(it) }.take(10)

/** Monta as fileiras da tela de um streaming (roda fora da linha principal). */
fun servicePage(ctx: Context, svcId: String): List<Row> {
    val (movies, series) = Catalog.serviceItems(svcId)
    if (movies.isEmpty() && series.isEmpty()) return emptyList()

    val ranks = Catalog.top10?.services?.get(svcId)
    val (mIdx, sIdx) = Catalog.serviceIndex(svcId)
    val topMovies = ranks?.let { Matcher.rank(it.movies, mIdx, Catalog.movieIndex) }
        .orEmpty().ifEmpty { bestRated(movies) }
    val topSeries = ranks?.let { Matcher.rank(it.series, sIdx, Catalog.seriesIndex) }
        .orEmpty().ifEmpty { bestRated(series) }

    val rows = ArrayList<Row>()
    var id = 1L

    val hero = (0 until 4).flatMap { i -> listOfNotNull(topMovies.getOrNull(i), topSeries.getOrNull(i)) }
        .ifEmpty { (movies.take(4) + series.take(4)) }
    if (hero.isNotEmpty()) {
        val a = ArrayObjectAdapter(CardPresenter(big = true))
        a.addAll(0, hero)
        rows.add(ListRow(HeaderItem(id++, "Destaques"), a))
    }
    if (topMovies.isNotEmpty()) rows.add(rankedRow(id++, "Top 10 filmes", topMovies))
    if (topSeries.isNotEmpty()) rows.add(rankedRow(id++, "Top 10 séries", topSeries))

    val hasHistory = History.list(ctx).isNotEmpty()
    val recs = Recs.forUser(ctx, movies + series)
    if (recs.isNotEmpty()) {
        val a = ArrayObjectAdapter(CardPresenter())
        a.addAll(0, recs)
        rows.add(ListRow(HeaderItem(id++, if (hasHistory) "Sugestões para você" else "Mais bem avaliados"), a))
    }

    val mine = { c: Category -> Streaming.match(c.name)?.id == svcId }
    for (s in shelves(Catalog.movieCats, movies, mine, withOthers = false)) {
        val a = ArrayObjectAdapter(CardPresenter())
        a.addAll(0, s.items)
        rows.add(ListRow(HeaderItem(id++, "Filmes · ${s.title}"), a))
    }
    for (s in shelves(Catalog.seriesCats, series, mine, withOthers = false)) {
        val a = ArrayObjectAdapter(CardPresenter())
        a.addAll(0, s.items)
        rows.add(ListRow(HeaderItem(id++, "Séries · ${s.title}"), a))
    }
    return rows
}

/** Busca geral ou só dentro de um streaming (quando aberta pela tela dele). */
class SearchFragment : SearchSupportFragment(), SearchSupportFragment.SearchResultProvider {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private val posters = CardPresenter()
    private var svcId: String? = null
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        svcId = requireActivity().intent.getStringExtra(EXTRA_SERVICE)
        requireActivity().intent.getStringExtra(EXTRA_SERVICE_NAME)?.let { title = "Buscar em $it" }
        setSearchResultProvider(this)
        setOnItemViewClickedListener(OnItemViewClickedListener { _, item, _, _ ->
            asItem(item)?.let { Nav.open(requireActivity(), it) }
        })
    }

    override fun getResultsAdapter(): ObjectAdapter = rowsAdapter

    override fun onQueryTextChange(newQuery: String): Boolean {
        search(newQuery)
        return true
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        search(query)
        return true
    }

    private fun search(q: String) {
        job?.cancel()
        val query = Matcher.searchKey(q)
        if (query.length < 2) {
            rowsAdapter.clear()
            return
        }
        val svc = svcId
        job = lifecycleScope.launch {
            delay(250)
            val found = withContext(Dispatchers.Default) {
                val allow = svc?.let { Catalog.serviceKeys(it) }
                fun pick(list: List<Pair<String, Item>>): List<Item> =
                    list.asSequence()
                        .filter { p -> p.first.contains(query) && (allow == null || p.second.key() in allow) }
                        .map { it.second }
                        .take(80)
                        .toList()
                pick(Catalog.movieSearch) to pick(Catalog.seriesSearch)
            }
            rowsAdapter.clear()
            if (found.first.isNotEmpty()) {
                val a = ArrayObjectAdapter(posters)
                a.addAll(0, found.first)
                rowsAdapter.add(ListRow(HeaderItem(1L, "Filmes (${found.first.size})"), a))
            }
            if (found.second.isNotEmpty()) {
                val a = ArrayObjectAdapter(posters)
                a.addAll(0, found.second)
                rowsAdapter.add(ListRow(HeaderItem(2L, "Séries (${found.second.size})"), a))
            }
        }
    }
}
