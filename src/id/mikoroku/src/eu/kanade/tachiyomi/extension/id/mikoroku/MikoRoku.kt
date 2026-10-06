package eu.kanade.tachiyomi.extension.id.mikoroku

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup

@Source
abstract class MikoRoku : KeiSource() {

    override suspend fun getPopularManga(page: Int): MangasPage = fetchCatalog()
        .sortedByDescending { it.rating }
        .toMangasPage()

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        // Fetch most-recent posts from both Blogger mirrors.
        val posts = buildList {
            addAll(fetchBloggerFeed("https://www.mikodrive.my.id/feeds/posts/default", 500))
            addAll(fetchBloggerFeed("https://www.yomidays.my.id/feeds/posts/default", 500))
        }
        val catalog = fetchCatalog()

        val seenSlugs = mutableSetOf<String>()
        val mangas = mutableListOf<SManga>()

        // 1. Group posts by their extracted manga title (newest updates first).
        for (post in posts.sortedByDescending { it.publishedDate }) {
            val postMangaTitle = post.mangaTitleFromPost() ?: continue
            val entry = catalog.firstOrNull { entry ->
                titleWordsMatch(postMangaTitle, entry.title) ||
                    entry.altTitle.split(";").any { alt -> titleWordsMatch(postMangaTitle, alt.trim()) }
            } ?: continue
            if (seenSlugs.add(entry.slug)) {
                mangas.add(
                    entry.toSManga().apply {
                        // Use blogger post thumbnail as fallback when catalog has no cover.
                        if (thumbnail_url.isNullOrBlank()) {
                            thumbnail_url = post.thumbnailUrl
                        }
                    },
                )
            }
        }

        // 2. Append remaining catalog manga so no manga is excluded (no limit).
        for (entry in catalog) {
            if (seenSlugs.add(entry.slug)) {
                mangas.add(entry.toSManga())
            }
        }

        return MangasPage(mangas, false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val normalizedQuery = query.normalize()
        var list = fetchCatalog()

        if (normalizedQuery.isNotEmpty()) {
            list = list.filter { entry ->
                entry.title.normalize().contains(normalizedQuery) ||
                    entry.altTitle.split(";").any { it.normalize().contains(normalizedQuery) }
            }
        }

        var sortOption = 0
        for (filter in filters) {
            when (filter) {
                is SortFilter -> sortOption = filter.state
                is StatusFilter -> {
                    if (filter.state > 0) {
                        val status = filter.values[filter.state].lowercase()
                        list = list.filter { it.status.equals(status, ignoreCase = true) }
                    }
                }
                is GenreFilter -> {
                    val included = filter.state.filter { it.isIncluded() }.map { it.name.lowercase() }
                    val excluded = filter.state.filter { it.isExcluded() }.map { it.name.lowercase() }
                    if (included.isNotEmpty() || excluded.isNotEmpty()) {
                        list = list.filter { entry ->
                            val entryGenres = entry.genres.map { it.lowercase() }
                            included.all { it in entryGenres } && excluded.none { it in entryGenres }
                        }
                    }
                }
                else -> {}
            }
        }

        list = when (sortOption) {
            1 -> { // Terbaru
                val posts = buildList {
                    addAll(fetchBloggerFeed("https://www.mikodrive.my.id/feeds/posts/default", 500))
                    addAll(fetchBloggerFeed("https://www.yomidays.my.id/feeds/posts/default", 500))
                }
                val slugToDate = mutableMapOf<String, Long>()
                for (post in posts) {
                    val postMangaTitle = post.mangaTitleFromPost() ?: continue
                    val matched = list.firstOrNull { entry ->
                        titleWordsMatch(postMangaTitle, entry.title) ||
                            entry.altTitle.split(";").any { alt -> titleWordsMatch(postMangaTitle, alt.trim()) }
                    } ?: continue
                    val existing = slugToDate[matched.slug] ?: 0L
                    if (post.publishedDate > existing) {
                        slugToDate[matched.slug] = post.publishedDate
                    }
                }
                list.sortedByDescending { slugToDate[it.slug] ?: 0L }
            }
            2 -> list.sortedByDescending { it.rating } // Populer (Rating tertinggi)
            3 -> list.sortedBy { it.rating } // Rating terendah
            4 -> list.sortedBy { it.title.lowercase() } // Judul A-Z
            5 -> list.sortedByDescending { it.title.lowercase() } // Judul Z-A
            else -> list
        }

        return list.toMangasPage()
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortFilter(),
        Filter.Separator(),
        StatusFilter(),
        Filter.Separator(),
        GenreFilter(),
    )

    private fun List<CatalogEntry>.toMangasPage() = MangasPage(map { it.toSManga() }, false)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val host = url.host.removePrefix("www.")
        if (host != "mikoroku.top" && host != "mikoroku.com") return null
        val slug = url.queryParameter("slug") ?: return null

        return buildManga(slug, fetchFirestoreDoc(slug)).apply { initialized = true }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slug = "$baseUrl${manga.url}".toHttpUrl().queryParameter("slug")
            ?: throw Exception("Invalid manga URL: ${manga.url}")

        val doc = fetchFirestoreDoc(slug)
        val details = buildManga(slug, doc)

        return SMangaUpdate(manga = details, chapters = fetchChapterList(slug, doc, details.title))
    }

    private suspend fun buildManga(slug: String, doc: FirestoreMap?): SManga {
        val fields = doc?.fields.orEmpty()
        val entry = fetchCatalog().find { it.slug == slug }
        return SManga.create().apply {
            url = "/detail.html?slug=$slug"
            title = fields.getString("title").nonBlank()
                ?: entry?.title
                ?: throw Exception("Manga tidak ditemukan")
            author = fields.getString("author").nonBlank() ?: entry?.author.nonBlank()
            artist = fields.getString("artist").nonBlank() ?: entry?.artist.nonBlank()
            description = fields.getString("desc").nonBlank()?.let { Jsoup.parseBodyFragment(it).text() }
                ?: entry?.desc.nonBlank()
            genre = fields.getStringList("genres").takeIf { it.isNotEmpty() }?.joinToString()
                ?: entry?.genres?.takeIf { it.isNotEmpty() }?.joinToString()
            status = (fields.getString("status") ?: entry?.status).toMangaStatus()
            thumbnail_url = fields.getString("img").resolveCover() ?: entry?.img.resolveCover()
        }
    }

    private fun String?.toMangaStatus(): Int = when (this?.lowercase()) {
        "ongoing" -> SManga.ONGOING
        "completed" -> SManga.COMPLETED
        "hiatus" -> SManga.ON_HIATUS
        "dropped", "cancelled" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    private suspend fun fetchChapterList(slug: String, doc: FirestoreMap?, title: String): List<SChapter> {
        val subcollectionChapters = fetchChaptersSubcollection(slug)
        val fieldChapters = doc?.toChapterInfos().orEmpty()
            .sortedWith(compareByDescending<ChapterInfo> { it.number }.thenByDescending { it.order })
            .map { it.toSChapter(slug) }
        val firestoreChapters = (subcollectionChapters + fieldChapters)
            .distinctBy { it.name.chapterNumber() }

        val blogger1 = searchBlogger(
            "https://www.mikodrive.my.id/feeds/posts/default",
            title,
            500,
        ).mapNotNull { it.toSChapter(slug, title) }
        val blogger2 = searchBlogger(
            "https://www.yomidays.my.id/feeds/posts/default",
            title,
            500,
        ).mapNotNull { it.toSChapter(slug, title) }
        val seen = firestoreChapters.mapTo(mutableSetOf()) { it.name.chapterNumber() }
        val merged = firestoreChapters.toMutableList()
        for (ch in blogger1 + blogger2) {
            if (seen.add(ch.name.chapterNumber())) merged.add(ch)
        }
        return merged.sortedByDescending { it.name.chapterNumber() }
    }

    private suspend fun fetchChaptersSubcollection(slug: String): List<SChapter> {
        val url = "https://firestore.googleapis.com/v1/projects/mikoroku/databases/(default)/documents/manga/$slug/chapters?pageSize=500"

        val response = client.get(url, ensureSuccess = false)
        if (response.code != 200) {
            response.close()
            return emptyList()
        }
        return response.parseAs<FirestoreListResponse>().documents.mapNotNull { doc ->
            val fields = doc.fields
            if (fields.getBoolean("isDraft") == true) return@mapNotNull null
            val title = fields.getString("title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val key = doc.name.substringAfterLast("/")
            SChapter.create().apply {
                name = title
                date_upload = fields.getLong("date") ?: 0L
                this.url = "/manga/$slug/chapter/$key"
            }
        }
    }

    private fun FirestoreMap.toChapterInfos(): List<ChapterInfo> = fields
        .filterKeys { it.startsWith("chapters.") }
        .mapNotNull { (key, value) ->
            val chapter = value.mapValue?.fields ?: return@mapNotNull null
            if (chapter.getBoolean("isDraft") == true) return@mapNotNull null

            ChapterInfo(
                key = key.removePrefix("chapters."),
                title = chapter.getString("title").nonBlank() ?: return@mapNotNull null,
                date = chapter.getLong("date") ?: 0L,
                order = chapter.getLong("order") ?: 0L,
            )
        }

    private class ChapterInfo(
        val key: String,
        val title: String,
        val date: Long,
        val order: Long,
    ) {
        val number = title.chapterNumber()

        fun toSChapter(slug: String) = SChapter.create().apply {
            name = title
            date_upload = date
            url = "/manga/$slug/chapter/$key"
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        POST_URL_REGEX.find(chapter.url)?.let { match ->
            return fetchBloggerPostPages(match.groupValues[2])
        }

        val (slug, key) = CHAPTER_URL_REGEX.find(chapter.url)?.destructured
            ?: throw Exception("Unsupported chapter URL: ${chapter.url}")

        fetchFirestorePages(slug, key)?.let { return it }

        val title = fetchCatalog().find { it.slug == slug }?.title ?: return emptyList()
        return fetchBloggerChapterPages(title, chapter.name)
    }

    private suspend fun fetchFirestorePages(slug: String, key: String): List<Page>? {
        val subcollectionImages = run {
            val url = "https://firestore.googleapis.com/v1/projects/mikoroku/databases/(default)/documents/manga/$slug/chapters/$key"
            val response = client.get(url, ensureSuccess = false)
            if (response.code != 200) {
                response.close()
                null
            } else {
                response.parseAs<FirestoreDoc>().fields.getStringList("images").takeIf { it.isNotEmpty() }
            }
        }
        if (!subcollectionImages.isNullOrEmpty()) {
            return subcollectionImages.toPages()
        }

        val fieldImages = fetchFirestoreDoc(slug)
            ?.fields?.get("chapters.$key")
            ?.mapValue?.fields
            ?.getStringList("images")
            .orEmpty()
        if (fieldImages.isEmpty()) return null

        return fieldImages.toPages()
    }

    private suspend fun fetchBloggerPostPages(postId: String): List<Page> {
        val url = "https://www.mikodrive.my.id/feeds/posts/default/$postId".toHttpUrl().newBuilder()
            .addQueryParameter("alt", "json")
            .build()

        return client.get(url).parseAs<BloggerEntryResponse>().entry.imageUrls().toPages()
    }

    private suspend fun fetchBloggerChapterPages(mangaTitle: String, chapterName: String): List<Page> {
        val label = chapterName.chapterLabel() ?: return emptyList()
        val number = label.chapterNumber()

        return searchBlogger("https://www.mikodrive.my.id/feeds/posts/default", "$mangaTitle $label", 5)
            .firstOrNull { it.chapterLabel(mangaTitle)?.chapterNumber() == number }
            ?.imageUrls()
            .orEmpty()
            .toPages()
    }

    private fun List<String>.toPages(): List<Page> = mapIndexed { index, url -> Page(index, imageUrl = url) }

    private suspend fun fetchCatalog(): List<CatalogEntry> {
        val catalog = client.get("https://raw.githubusercontent.com/moemaomao/mymangadata/main/all-manga.json").parseAs<List<CatalogEntry>>()
        return catalog
    }

    private suspend fun fetchFirestoreDoc(slug: String): FirestoreMap? {
        val url = "https://firestore.googleapis.com/v1/projects/mikoroku/databases/(default)/documents/manga/$slug"

        val response = client.get(url, ensureSuccess = false)
        if (response.code != 200) {
            response.close()
            return null
        }
        return response.parseAs<FirestoreMap>()
    }

    private suspend fun searchBlogger(feedUrl: String, query: String, maxResults: Int): List<BloggerEntry> {
        searchBloggerWithQuery(feedUrl, query, maxResults).takeIf { it.isNotEmpty() }?.let { return it }

        // Blogger's q= doesn't match "2" with "II", so retry with the
        // trailing number/roman numeral stripped (e.g. "isekai furin 2" -> "isekai furin").
        val stripped = query.replace(Regex("\\s+(\\d+|[ivxl]+)$", RegexOption.IGNORE_CASE), "").trim()
        if (stripped.isNotBlank() && stripped != query) {
            searchBloggerWithQuery(feedUrl, stripped, maxResults).takeIf { it.isNotEmpty() }?.let { return it }
        }

        // For conversion mismatches (e.g. "konten" vs "tamashiten"),
        // trying progressively shorter queries. The titleWordsMatch filter in toSChapter will reject false positives.
        val words = query.split(Regex("\\s+")).filter { it.isNotBlank() }
        for (n in listOf(4, 3)) {
            if (words.size > n) {
                val short = words.take(n).joinToString(" ")
                searchBloggerWithQuery(feedUrl, short, maxResults).takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return emptyList()
    }

    private suspend fun searchBloggerWithQuery(feedUrl: String, query: String, maxResults: Int): List<BloggerEntry> {
        val url = feedUrl.toHttpUrl().newBuilder()
            .addQueryParameter("alt", "json")
            .addQueryParameter("max-results", maxResults.toString())
            .addQueryParameter("q", query)
            .build()

        return client.get(url).parseAs<BloggerFeedResponse>().feed.entry
    }

    private suspend fun fetchBloggerFeed(feedUrl: String, maxResults: Int): List<BloggerEntry> {
        val url = feedUrl.toHttpUrl().newBuilder()
            .addQueryParameter("alt", "json")
            .addQueryParameter("max-results", maxResults.toString())
            .addQueryParameter("orderby", "published")
            .build()

        return client.get(url).parseAs<BloggerFeedResponse>().feed.entry
    }

    private fun String.normalize(): String = normalized()

    private fun String?.nonBlank(): String? = this?.takeIf { it.isNotBlank() }

    companion object {
        private val CHAPTER_URL_REGEX = """/manga/([^/]+)/chapter/([^/]+)""".toRegex()
        private val POST_URL_REGEX = """/manga/([^/]+)/post/([^/]+)""".toRegex()
    }
}
