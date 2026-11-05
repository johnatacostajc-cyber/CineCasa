package com.johnata.cinecasa.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.DividerRow
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.SectionRow
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.bumptech.glide.Glide
import com.johnata.cinecasa.R
import com.johnata.cinecasa.data.Account
import com.johnata.cinecasa.data.Catalog
import com.johnata.cinecasa.data.Category
import com.johnata.cinecasa.data.History
import com.johnata.cinecasa.data.Item
import com.johnata.cinecasa.data.Kind
import com.johnata.cinecasa.data.Names
import com.johnata.cinecasa.data.Prefs
import com.johnata.cinecasa.data.Streaming
import com.johnata.cinecasa.data.Xtream
import java.text.Normalizer
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** Desenha cada card (capa + título). wide = formato 16:9 para episódios. */
class CardPresenter(private val wide: Boolean = false) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        val w = ((if (wide) 260 else 150) * d).toInt()
        val h = ((if (wide) 146 else 225) * d).toInt()
        val card = ImageCardView(ctx)
        card.isFocusable = true
        card.isFocusableInTouchMode = true
        card.setMainImageDimensions(w, h)
        card.setInfoAreaBackgroundColor(ContextCompat.getColor(ctx, R.color.card_info))
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val it = item as? Item ?: return
        val card = viewHolder.view as ImageCardView
        card.titleText = it.title
        card.contentText = when {
            it.progress >= 0 -> "${it.progress}% assistido" + (it.subtitle?.let { s -> " · $s" } ?: "")
            it.subtitle != null -> it.subtitle
            it.rating != null -> "★ ${it.rating}"
            else -> null
        }
        Glide.with(card.context)
            .load(it.poster)
            .centerCrop()
            .placeholder(placeholder())
            .error(placeholder())
            .into(card.mainImageView)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val card = viewHolder.view as ImageCardView
        Glide.with(card.context).clear(card.mainImageView)
        card.mainImage = null
    }
}

/** Tela do filme: capa, sinopse e botões Assistir / Continuar. */
class DetailsActivity : FragmentActivity() {

    private lateinit var item: Item
    private lateinit var btnPlay: Button
    private lateinit var btnResume: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val it = intent.getItem()
        if (it == null) {
            finish()
            return
        }
        item = it
        setContentView(R.layout.activity_details)

        val poster = findViewById<ImageView>(R.id.img_poster)
        val backdrop = findViewById<ImageView>(R.id.img_backdrop)
        val title = findViewById<TextView>(R.id.txt_title)
        val meta = findViewById<TextView>(R.id.txt_meta)
        val plot = findViewById<TextView>(R.id.txt_plot)
        btnPlay = findViewById(R.id.btn_play)
        btnResume = findViewById(R.id.btn_resume)

        title.text = item.title
        meta.text = item.rating?.let { r -> "★ $r" } ?: ""
        Glide.with(this).load(item.poster).placeholder(placeholder()).centerCrop().into(poster)
        Glide.with(this).load(item.poster).centerCrop().into(backdrop)

        btnPlay.setOnClickListener { Nav.play(this, item, 0L) }
        btnResume.setOnClickListener { Nav.play(this, item, History.position(this, item)) }

        val acc = Prefs.account(this) ?: return
        lifecycleScope.launch {
            val info = Xtream.movieInfo(acc, item.id) ?: return@launch
            val parts = listOfNotNull(
                info.year,
                info.genre,
                info.duration,
                (info.rating ?: item.rating)?.let { r -> "★ $r" },
            )
            meta.text = parts.joinToString("   •   ")
            plot.text = info.plot ?: ""
            if (info.poster != null && item.poster == null) {
                Glide.with(this@DetailsActivity).load(info.poster).centerCrop().into(poster)
            }
            val bg = info.backdrop ?: info.poster
            if (bg != null) Glide.with(this@DetailsActivity).load(bg).centerCrop().into(backdrop)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::item.isInitialized) return
        val pos = History.position(this, item)
        if (pos > 0) {
            btnResume.visibility = View.VISIBLE
            btnResume.text = "▶  Continuar de ${Nav.time(pos)}"
            btnPlay.text = getString(R.string.from_start)
            btnResume.requestFocus()
        } else {
            btnResume.visibility = View.GONE
            btnPlay.text = getString(R.string.play)
            btnPlay.requestFocus()
        }
    }
}

class LoginActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val server = findViewById<EditText>(R.id.edit_server)
        val user = findViewById<EditText>(R.id.edit_user)
        val pass = findViewById<EditText>(R.id.edit_pass)
        val btn = findViewById<Button>(R.id.btn_login)
        val status = findViewById<TextView>(R.id.txt_status)

        server.requestFocus()

        btn.setOnClickListener {
            val acc = Account.build(server.text.toString(), user.text.toString(), pass.text.toString())
            if (acc == null) {
                status.text = "Preencha o link do servidor, o usuário e a senha."
                return@setOnClickListener
            }
            btn.isEnabled = false
            status.text = "Conectando…"
            lifecycleScope.launch {
                val result = try {
                    if (Xtream.login(acc)) null else "Usuário ou senha não aceitos pelo servidor."
                } catch (e: Exception) {
                    "Não consegui falar com o servidor: ${e.message ?: e.javaClass.simpleName}"
                }
                btn.isEnabled = true
                if (result == null) {
                    Prefs.save(this@LoginActivity, acc)
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                } else {
                    status.text = result
                }
            }
        }
    }
}

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs.account(this) == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_frame)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_frame, MainFragment())
                .commit()
        }
    }
}

class MainFragment : BrowseSupportFragment() {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private val posters = CardPresenter()
    private var continueRow: ListRow? = null
    private var loaded = false

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
            if (item is Item) Nav.open(requireActivity(), item)
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        if (loaded) refreshContinue()
    }

    private fun load() {
        val acc = Prefs.account(requireContext()) ?: return
        Toast.makeText(requireContext(), "Carregando catálogo…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val mCats = async { runCatching { Xtream.movieCategories(acc) }.getOrDefault(emptyList()) }
            val mList = async { runCatching { Xtream.movies(acc) }.getOrDefault(emptyList()) }
            val sCats = async { runCatching { Xtream.seriesCategories(acc) }.getOrDefault(emptyList()) }
            val sList = async { runCatching { Xtream.series(acc) }.getOrDefault(emptyList()) }

            val movies = mList.await()
            val series = sList.await()
            val movieCats = mCats.await()
            val seriesCats = sCats.await()
            Catalog.movies = movies
            Catalog.series = series
            Catalog.movieCats = movieCats
            Catalog.seriesCats = seriesCats

            rowsAdapter.clear()
            continueRow = null
            refreshContinue()

            var headerId = 10L

            // Fileira de streamings (Netflix, Disney+...) logo abaixo de "Continuar assistindo".
            val services = Streaming.present(movieCats, movies, seriesCats, series)
            if (services.isNotEmpty()) {
                val a = ArrayObjectAdapter(ServicePresenter())
                a.addAll(0, services.map { Item(Kind.SERVICE, it.id, it.name) })
                rowsAdapter.add(ListRow(HeaderItem(headerId++, "Streamings"), a))
            }

            val recent = movies.filter { it.added > 0 }.sortedByDescending { it.added }.take(40)
            if (recent.isNotEmpty()) addRow(headerId++, "Adicionados recentemente", recent)

            // Categorias organizadas: nomes limpos, repetidas juntas e as de streaming ficam nos botões.
            val notService = { c: Category -> Streaming.match(c.name) == null }
            val movieShelves = shelves(movieCats, movies, notService, withOthers = true)
            if (movieShelves.isNotEmpty()) {
                rowsAdapter.add(SectionRow(HeaderItem(headerId++, "Filmes")))
                for (s in movieShelves) addRow(headerId++, s.title, s.items)
            }
            val seriesShelves = shelves(seriesCats, series, notService, withOthers = true)
            if (seriesShelves.isNotEmpty()) {
                rowsAdapter.add(SectionRow(HeaderItem(headerId++, "Séries")))
                for (s in seriesShelves) addRow(headerId++, s.title, s.items)
            }

            rowsAdapter.add(DividerRow())
            addRow(headerId, "Conta", listOf(Item(Kind.ACTION, "logout", "Trocar conta / sair")))
            loaded = true

            if (movies.isEmpty() && series.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    "Não encontrei filmes nem séries nessa lista. Confira a internet ou os dados de acesso.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun addRow(id: Long, name: String, items: List<Item>) {
        val a = ArrayObjectAdapter(posters)
        a.addAll(0, items)
        rowsAdapter.add(ListRow(HeaderItem(id, name), a))
    }

    private fun refreshContinue() {
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

/** Uma fileira pronta: título já limpo e os itens dela. */
class Shelf(val title: String, val items: List<Item>)

/**
 * Junta os itens por categoria, limpa os nomes ("FILMES | AÇÃO" vira "Ação")
 * e une categorias que ficaram com o mesmo nome.
 */
fun shelves(cats: List<Category>, items: List<Item>, include: (Category) -> Boolean, withOthers: Boolean): List<Shelf> {
    if (items.isEmpty()) return emptyList()
    val byCat = items.groupBy { it.categoryId ?: "" }
    val merged = LinkedHashMap<String, Pair<String, MutableList<Item>>>()
    val known = HashSet<String>()
    for (c in cats) {
        known.add(c.id)
        if (!include(c)) continue
        val list = byCat[c.id] ?: continue
        if (list.isEmpty()) continue
        val title = Names.clean(c.name)
        val key = Streaming.norm(title)
        merged.getOrPut(key) { Pair<String, MutableList<Item>>(title, ArrayList()) }.second.addAll(list)
    }
    val out = merged.values.map { (t, l) -> Shelf(t, l.distinctBy { it.id }) }.toMutableList()
    if (withOthers) {
        val others = items.filter { (it.categoryId ?: "") !in known }
        if (others.isNotEmpty()) out.add(Shelf("Outros", others))
    }
    return out
}

/** Visual de cada streaming: cores do degradê e um ícone original. */
class ServiceStyle(val top: Int, val bottom: Int, val icon: Int)

object ServiceStyles {
    private val map = mapOf(
        "netflix" to ServiceStyle(0xFF8E44EC.toInt(), 0xFF3B1C7A.toInt(), R.drawable.ic_svc_play),
        "disney" to ServiceStyle(0xFF1FB7A6.toInt(), 0xFF0D5A55.toInt(), R.drawable.ic_svc_crown),
        "prime" to ServiceStyle(0xFFF2994A.toInt(), 0xFF8A3D12.toInt(), R.drawable.ic_svc_film),
        "max" to ServiceStyle(0xFFE0479E.toInt(), 0xFF6E1450.toInt(), R.drawable.ic_svc_diamond),
        "paramount" to ServiceStyle(0xFF43B581.toInt(), 0xFF185A3A.toInt(), R.drawable.ic_svc_tv),
        "globoplay" to ServiceStyle(0xFF5B6CF0.toInt(), 0xFF232C7A.toInt(), R.drawable.ic_svc_heart),
        "apple" to ServiceStyle(0xFF7A8BA6.toInt(), 0xFF2E3644.toInt(), R.drawable.ic_svc_sparkle),
        "crunchyroll" to ServiceStyle(0xFFF06292.toInt(), 0xFF7A1F42.toInt(), R.drawable.ic_svc_bolt),
        "discovery" to ServiceStyle(0xFFE6B422.toInt(), 0xFF6B4E06.toInt(), R.drawable.ic_svc_leaf),
    )
    private val fallback = ServiceStyle(0xFF3A3A48.toInt(), 0xFF16161D.toInt(), R.drawable.ic_svc_play)

    fun of(id: String): ServiceStyle = map[id] ?: fallback
}

/** Botão grande de cada streaming: degradê colorido, ícone e nome. */
class ServicePresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.layoutParams = ViewGroup.LayoutParams((240 * d).toInt(), (135 * d).toInt())
        box.isFocusable = true
        box.isFocusableInTouchMode = true

        val icon = ImageView(ctx)
        icon.layoutParams = LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt())
        icon.setColorFilter(Color.WHITE)
        box.addView(icon)

        val name = TextView(ctx)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = (10 * d).toInt()
        name.layoutParams = lp
        name.setTextColor(Color.WHITE)
        name.textSize = 22f
        name.typeface = Typeface.DEFAULT_BOLD
        name.maxLines = 1
        name.setShadowLayer(6f, 0f, 2f, 0x66000000)
        box.addView(name)
        return ViewHolder(box)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val it = item as? Item ?: return
        val box = viewHolder.view as LinearLayout
        val d = box.resources.displayMetrics.density
        val st = ServiceStyles.of(it.id)

        val normal = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(st.top, st.bottom))
        normal.cornerRadius = 16 * d
        val focused = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(st.top, st.bottom))
        focused.cornerRadius = 16 * d
        focused.setStroke((3 * d).toInt(), Color.WHITE)
        val states = StateListDrawable()
        states.addState(intArrayOf(android.R.attr.state_focused), focused)
        states.addState(intArrayOf(), normal)
        box.background = states

        (box.getChildAt(0) as ImageView).setImageResource(st.icon)
        (box.getChildAt(1) as TextView).text = it.title
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {}
}

class ServiceActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_frame)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_frame, ServiceFragment())
                .commit()
        }
    }
}

/** Tela de um streaming: filmes e séries das categorias dele. */
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
        adapter = rowsAdapter
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is Item) Nav.open(requireActivity(), item)
        }

        val posters = CardPresenter()
        val mine = { c: Category -> Streaming.match(c.name)?.id == svc.id }
        var id = 1L
        for (s in shelves(Catalog.movieCats, Catalog.movies, mine, withOthers = false)) {
            val a = ArrayObjectAdapter(posters)
            a.addAll(0, s.items)
            rowsAdapter.add(ListRow(HeaderItem(id++, "Filmes · ${s.title}"), a))
        }
        for (s in shelves(Catalog.seriesCats, Catalog.series, mine, withOthers = false)) {
            val a = ArrayObjectAdapter(posters)
            a.addAll(0, s.items)
            rowsAdapter.add(ListRow(HeaderItem(id++, "Séries · ${s.title}"), a))
        }
        if (rowsAdapter.size() == 0) {
            Toast.makeText(requireContext(), "Nada encontrado. Volte à tela inicial e tente de novo.", Toast.LENGTH_LONG).show()
        }
    }
}

const val EXTRA_ITEM = "item"
const val EXTRA_START = "start"

@Suppress("DEPRECATION")
fun Intent.getItem(): Item? = getSerializableExtra(EXTRA_ITEM) as? Item

object Nav {
    fun open(activity: Activity, item: Item) {
        when (item.kind) {
            Kind.MOVIE -> activity.startActivity(
                Intent(activity, DetailsActivity::class.java).putExtra(EXTRA_ITEM, item.copy(progress = -1))
            )
            Kind.SERIES -> activity.startActivity(
                Intent(activity, SeriesActivity::class.java).putExtra(EXTRA_ITEM, item)
            )
            Kind.EPISODE -> play(activity, item, History.position(activity, item))
            Kind.SERVICE -> activity.startActivity(
                Intent(activity, ServiceActivity::class.java).putExtra(EXTRA_ITEM, item)
            )
            Kind.ACTION -> if (item.id == "logout") {
                Prefs.logout(activity)
                activity.startActivity(
                    Intent(activity, LoginActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
            }
        }
    }

    fun play(activity: Activity, item: Item, startMs: Long) {
        activity.startActivity(
            Intent(activity, PlayerActivity::class.java)
                .putExtra(EXTRA_ITEM, item.copy(progress = -1))
                .putExtra(EXTRA_START, startMs)
        )
    }

    fun time(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : FragmentActivity() {

    private lateinit var item: Item
    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private var resumeAt = 0L
    private var ended = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val it = intent.getItem()
        if (it == null) {
            finish()
            return
        }
        item = it
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
        resumeAt = savedInstanceState?.getLong("pos") ?: intent.getLongExtra(EXTRA_START, 0L)
    }

    override fun onStart() {
        super.onStart()
        if (::item.isInitialized) initPlayer()
    }

    override fun onStop() {
        savePosition()
        player?.release()
        player = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong("pos", resumeAt)
    }

    private fun initPlayer() {
        if (player != null) return
        val acc = Prefs.account(this) ?: run { finish(); return }

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Xtream.UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)

        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .build()

        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    ended = true
                    History.remove(this@PlayerActivity, item)
                    finish()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    this@PlayerActivity,
                    "Não foi possível reproduzir este vídeo (${error.errorCodeName}).",
                    Toast.LENGTH_LONG
                ).show()
            }
        })

        playerView.player = p
        p.setMediaItem(MediaItem.fromUri(Xtream.streamUrl(acc, item)), resumeAt)
        p.playWhenReady = true
        p.prepare()
        player = p
        playerView.requestFocus()
    }

    private fun savePosition() {
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration.let { d -> if (d > 0) d else 0L }
        resumeAt = pos
        if (!ended) History.save(this, item, pos, dur)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::playerView.isInitialized) return super.dispatchKeyEvent(event)
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (playerView.isControllerFullyVisible) {
                if (event.action == KeyEvent.ACTION_UP) playerView.hideController()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }
}

class SearchActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_frame)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_frame, SearchFragment())
                .commit()
        }
    }
}

class SearchFragment : SearchSupportFragment(), SearchSupportFragment.SearchResultProvider {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private val posters = CardPresenter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSearchResultProvider(this)
        setOnItemViewClickedListener(OnItemViewClickedListener { _, item, _, _ ->
            if (item is Item) Nav.open(requireActivity(), item)
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

    private fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()

    private fun search(q: String) {
        rowsAdapter.clear()
        val query = norm(q.trim())
        if (query.length < 2) return
        val movies = Catalog.movies.filter { norm(it.title).contains(query) }.take(80)
        val series = Catalog.series.filter { norm(it.title).contains(query) }.take(80)
        if (movies.isNotEmpty()) {
            val a = ArrayObjectAdapter(posters)
            a.addAll(0, movies)
            rowsAdapter.add(ListRow(HeaderItem(1L, "Filmes (${movies.size})"), a))
        }
        if (series.isNotEmpty()) {
            val a = ArrayObjectAdapter(posters)
            a.addAll(0, series)
            rowsAdapter.add(ListRow(HeaderItem(2L, "Séries (${series.size})"), a))
        }
    }
}

class SeriesActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_frame)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_frame, SeriesFragment())
                .commit()
        }
    }
}

/** Lista as temporadas da série, uma fileira por temporada. */
class SeriesFragment : BrowseSupportFragment() {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val series = requireActivity().intent.getItem()
        if (series == null) {
            requireActivity().finish()
            return
        }
        title = series.title
        headersState = HEADERS_DISABLED
        brandColor = ContextCompat.getColor(requireContext(), R.color.brand)
        adapter = rowsAdapter
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is Item) Nav.open(requireActivity(), item)
        }

        val acc = Prefs.account(requireContext()) ?: return
        lifecycleScope.launch {
            val info = try {
                Xtream.seriesInfo(acc, series)
            } catch (e: Exception) {
                null
            }
            if (info == null || info.seasons.isEmpty()) {
                Toast.makeText(requireContext(), "Não encontrei episódios dessa série.", Toast.LENGTH_LONG).show()
                return@launch
            }
            val episodes = CardPresenter(wide = true)
            var id = 1L
            for ((name, eps) in info.seasons) {
                val a = ArrayObjectAdapter(episodes)
                a.addAll(0, eps)
                rowsAdapter.add(ListRow(HeaderItem(id++, name), a))
            }
        }
    }
}

fun placeholder() = ColorDrawable(0xFF22222A.toInt())
package com.johnata.cinecasa.ui

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.Presenter
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.bumptech.glide.Glide
import com.johnata.cinecasa.R
import com.johnata.cinecasa.data.Account
import com.johnata.cinecasa.data.Catalog
import com.johnata.cinecasa.data.Category
import com.johnata.cinecasa.data.History
import com.johnata.cinecasa.data.Item
import com.johnata.cinecasa.data.Kind
import com.johnata.cinecasa.data.Prefs
import com.johnata.cinecasa.data.Xtream
import java.text.Normalizer
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** Desenha cada card (capa + título). wide = formato 16:9 para episódios. */
class CardPresenter(private val wide: Boolean = false) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        val w = ((if (wide) 260 else 150) * d).toInt()
        val h = ((if (wide) 146 else 225) * d).toInt()
        val card = ImageCardView(ctx)
        card.isFocusable = true
        card.isFocusableInTouchMode = true
        card.setMainImageDimensions(w, h)
        card.setInfoAreaBackgroundColor(ContextCompat.getColor(ctx, R.color.card_info))
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val it = item as? Item ?: return
        val card = viewHolder.view as ImageCardView
        card.titleText = it.title
        card.contentText = when {
            it.progress >= 0 -> "${it.progress}% assistido" + (it.subtitle?.let { s -> " · $s" } ?: "")
            it.subtitle != null -> it.subtitle
            it.rating != null -> "★ ${it.rating}"
            else -> null
        }
        Glide.with(card.context)
            .load(it.poster)
            .centerCrop()
            .placeholder(placeholder())
            .error(placeholder())
            .into(card.mainImageView)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val card = viewHolder.view as ImageCardView
        Glide.with(card.context).clear(card.mainImageView)
        card.mainImage = null
    }
}

/** Tela do filme: capa, sinopse e botões Assistir / Continuar. */
class DetailsActivity : FragmentActivity() {

    private lateinit var item: Item
    private lateinit var btnPlay: Button
    private lateinit var btnResume: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val it = intent.getItem()
        if (it == null) {
            finish()
            return
        }
        item = it
        setContentView(R.layout.activity_details)

        val poster = findViewById<ImageView>(R.id.img_poster)
        val backdrop = findViewById<ImageView>(R.id.img_backdrop)
        val title = findViewById<TextView>(R.id.txt_title)
        val meta = findViewById<TextView>(R.id.txt_meta)
        val plot = findViewById<TextView>(R.id.txt_plot)
        btnPlay = findViewById(R.id.btn_play)
        btnResume = findViewById(R.id.btn_resume)

        title.text = item.title
        meta.text = item.rating?.let { r -> "★ $r" } ?: ""
        Glide.with(this).load(item.poster).placeholder(placeholder()).centerCrop().into(poster)
        Glide.with(this).load(item.poster).centerCrop().into(backdrop)

        btnPlay.setOnClickListener { Nav.play(this, item, 0L) }
        btnResume.setOnClickListener { Nav.play(this, item, History.position(this, item)) }

        val acc = Prefs.account(this) ?: return
        lifecycleScope.launch {
            val info = Xtream.movieInfo(acc, item.id) ?: return@launch
            val parts = listOfNotNull(
                info.year,
                info.genre,
                info.duration,
                (info.rating ?: item.rating)?.let { r -> "★ $r" },
            )
            meta.text = parts.joinToString("   •   ")
            plot.text = info.plot ?: ""
            if (info.poster != null && item.poster == null) {
                Glide.with(this@DetailsActivity).load(info.poster).centerCrop().into(poster)
            }
            val bg = info.backdrop ?: info.poster
            if (bg != null) Glide.with(this@DetailsActivity).load(bg).centerCrop().into(backdrop)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::item.isInitialized) return
        val pos = History.position(this, item)
        if (pos > 0) {
            btnResume.visibility = View.VISIBLE
            btnResume.text = "▶  Continuar de ${Nav.time(pos)}"
            btnPlay.text = getString(R.string.from_start)
            btnResume.requestFocus()
        } else {
            btnResume.visibility = View.GONE
            btnPlay.text = getString(R.string.play)
            btnPlay.requestFocus()
        }
    }
}

class LoginActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val server = findViewById<EditText>(R.id.edit_server)
        val user = findViewById<EditText>(R.id.edit_user)
        val pass = findViewById<EditText>(R.id.edit_pass)
        val btn = findViewById<Button>(R.id.btn_login)
        val status = findViewById<TextView>(R.id.txt_status)

        server.requestFocus()

        btn.setOnClickListener {
            val acc = Account.build(server.text.toString(), user.text.toString(), pass.text.toString())
            if (acc == null) {
                status.text = "Preencha o link do servidor, o usuário e a senha."
                return@setOnClickListener
            }
            btn.isEnabled = false
            status.text = "Conectando…"
            lifecycleScope.launch {
                val result = try {
                    if (Xtream.login(acc)) null else "Usuário ou senha não aceitos pelo servidor."
                } catch (e: Exception) {
                    "Não consegui falar com o servidor: ${e.message ?: e.javaClass.simpleName}"
                }
                btn.isEnabled = true
                if (result == null) {
                    Prefs.save(this@LoginActivity, acc)
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                } else {
                    status.text = result
                }
            }
        }
    }
}

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs.account(this) == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_frame)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_frame, MainFragment())
                .commit()
        }
    }
}

class MainFragment : BrowseSupportFragment() {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private val posters = CardPresenter()
    private var continueRow: ListRow? = null
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = ContextCompat.getColor(requireContext(), R.color.brand)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.accent)
        adapter = rowsAdapter

        setOnSearchClickedListener {
            startActivity(Intent(requireContext(), SearchActivity::class.java))
        }
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is Item) Nav.open(requireActivity(), item)
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        if (loaded) refreshContinue()
    }

    private fun load() {
        val acc = Prefs.account(requireContext()) ?: return
        Toast.makeText(requireContext(), "Carregando catálogo…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val mCats = async { runCatching { Xtream.movieCategories(acc) }.getOrDefault(emptyList()) }
            val mList = async { runCatching { Xtream.movies(acc) }.getOrDefault(emptyList()) }
            val sCats = async { runCatching { Xtream.seriesCategories(acc) }.getOrDefault(emptyList()) }
            val sList = async { runCatching { Xtream.series(acc) }.getOrDefault(emptyList()) }

            val movies = mList.await()
            val series = sList.await()
            Catalog.movies = movies
            Catalog.series = series

            rowsAdapter.clear()
            continueRow = null
            refreshContinue()

            var headerId = 10L
            if (movies.isNotEmpty()) {
                val recent = movies.filter { it.added > 0 }.sortedByDescending { it.added }.take(40)
                if (recent.isNotEmpty()) addRow(headerId++, "Adicionados recentemente", recent)
            }
            headerId = addCategoryRows(headerId, "Filmes", mCats.await(), movies)
            headerId = addCategoryRows(headerId, "Séries", sCats.await(), series)

            addRow(headerId, "Conta", listOf(Item(Kind.ACTION, "logout", "Trocar conta / sair")))
            loaded = true

            if (movies.isEmpty() && series.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    "Não encontrei filmes nem séries nessa lista. Confira a internet ou os dados de acesso.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun addRow(id: Long, name: String, items: List<Item>) {
        val a = ArrayObjectAdapter(posters)
        a.addAll(0, items)
        rowsAdapter.add(ListRow(HeaderItem(id, name), a))
    }

    private fun addCategoryRows(startId: Long, prefix: String, cats: List<Category>, items: List<Item>): Long {
        var id = startId
        if (items.isEmpty()) return id
        val byCat = items.groupBy { it.categoryId ?: "" }
        val known = HashSet<String>()
        for (c in cats) {
            known.add(c.id)
            val list = byCat[c.id] ?: continue
            if (list.isNotEmpty()) addRow(id++, "$prefix · ${c.name}", list)
        }
        val others = items.filter { (it.categoryId ?: "") !in known }
        if (others.isNotEmpty()) addRow(id++, "$prefix · Outros", others)
        return id
    }

    private fun refreshContinue() {
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

const val EXTRA_ITEM = "item"
const val EXTRA_START = "start"

@Suppress("DEPRECATION")
fun Intent.getItem(): Item? = getSerializableExtra(EXTRA_ITEM) as? Item

object Nav {
    fun open(activity: Activity, item: Item) {
        when (item.kind) {
            Kind.MOVIE -> activity.startActivity(
                Intent(activity, DetailsActivity::class.java).putExtra(EXTRA_ITEM, item.copy(progress = -1))
            )
            Kind.SERIES -> activity.startActivity(
                Intent(activity, SeriesActivity::class.java).putExtra(EXTRA_ITEM, item)
            )
            Kind.EPISODE -> play(activity, item, History.position(activity, item))
            Kind.ACTION -> if (item.id == "logout") {
                Prefs.logout(activity)
                activity.startActivity(
                    Intent(activity, LoginActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
            }
        }
    }

    fun play(activity: Activity, item: Item, startMs: Long) {
        activity.startActivity(
            Intent(activity, PlayerActivity::class.java)
                .putExtra(EXTRA_ITEM, item.copy(progress = -1))
                .putExtra(EXTRA_START, startMs)
        )
    }

    fun time(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : FragmentActivity() {

    private lateinit var item: Item
    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private var resumeAt = 0L
    private var ended = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val it = intent.getItem()
        if (it == null) {
            finish()
            return
        }
        item = it
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
        resumeAt = savedInstanceState?.getLong("pos") ?: intent.getLongExtra(EXTRA_START, 0L)
    }

    override fun onStart() {
        super.onStart()
        if (::item.isInitialized) initPlayer()
    }

    override fun onStop() {
        savePosition()
        player?.release()
        player = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong("pos", resumeAt)
    }

    private fun initPlayer() {
        if (player != null) return
        val acc = Prefs.account(this) ?: run { finish(); return }

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Xtream.UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)

        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .build()

        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    ended = true
                    History.remove(this@PlayerActivity, item)
                    finish()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    this@PlayerActivity,
                    "Não foi possível reproduzir este vídeo (${error.errorCodeName}).",
                    Toast.LENGTH_LONG
                ).show()
            }
        })

        playerView.player = p
        p.setMediaItem(MediaItem.fromUri(Xtream.streamUrl(acc, item)), resumeAt)
        p.playWhenReady = true
        p.prepare()
        player = p
        playerView.requestFocus()
    }

    private fun savePosition() {
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration.let { d -> if (d > 0) d else 0L }
        resumeAt = pos
        if (!ended) History.save(this, item, pos, dur)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::playerView.isInitialized) return super.dispatchKeyEvent(event)
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (playerView.isControllerFullyVisible) {
                if (event.action == KeyEvent.ACTION_UP) playerView.hideController()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }
}

class SearchActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_frame)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_frame, SearchFragment())
                .commit()
        }
    }
}

class SearchFragment : SearchSupportFragment(), SearchSupportFragment.SearchResultProvider {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private val posters = CardPresenter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSearchResultProvider(this)
        setOnItemViewClickedListener(OnItemViewClickedListener { _, item, _, _ ->
            if (item is Item) Nav.open(requireActivity(), item)
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

    private fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()

    private fun search(q: String) {
        rowsAdapter.clear()
        val query = norm(q.trim())
        if (query.length < 2) return
        val movies = Catalog.movies.filter { norm(it.title).contains(query) }.take(80)
        val series = Catalog.series.filter { norm(it.title).contains(query) }.take(80)
        if (movies.isNotEmpty()) {
            val a = ArrayObjectAdapter(posters)
            a.addAll(0, movies)
            rowsAdapter.add(ListRow(HeaderItem(1L, "Filmes (${movies.size})"), a))
        }
        if (series.isNotEmpty()) {
            val a = ArrayObjectAdapter(posters)
            a.addAll(0, series)
            rowsAdapter.add(ListRow(HeaderItem(2L, "Séries (${series.size})"), a))
        }
    }
}

class SeriesActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_frame)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_frame, SeriesFragment())
                .commit()
        }
    }
}

/** Lista as temporadas da série, uma fileira por temporada. */
class SeriesFragment : BrowseSupportFragment() {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val series = requireActivity().intent.getItem()
        if (series == null) {
            requireActivity().finish()
            return
        }
        title = series.title
        headersState = HEADERS_DISABLED
        brandColor = ContextCompat.getColor(requireContext(), R.color.brand)
        adapter = rowsAdapter
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is Item) Nav.open(requireActivity(), item)
        }

        val acc = Prefs.account(requireContext()) ?: return
        lifecycleScope.launch {
            val info = try {
                Xtream.seriesInfo(acc, series)
            } catch (e: Exception) {
                null
            }
            if (info == null || info.seasons.isEmpty()) {
                Toast.makeText(requireContext(), "Não encontrei episódios dessa série.", Toast.LENGTH_LONG).show()
                return@launch
            }
            val episodes = CardPresenter(wide = true)
            var id = 1L
            for ((name, eps) in info.seasons) {
                val a = ArrayObjectAdapter(episodes)
                a.addAll(0, eps)
                rowsAdapter.add(ListRow(HeaderItem(id++, name), a))
            }
        }
    }
}

fun placeholder() = ColorDrawable(0xFF22222A.toInt())
