package eu.kanade.tachiyomi.extension.id.mikoroku

import eu.kanade.tachiyomi.source.model.Filter

class SortFilter :
    Filter.Select<String>(
        "Urutkan",
        arrayOf(
            "Default",
            "Terbaru",
            "Populer (Rating Tertinggi)",
            "Rating Terendah",
            "Judul (A-Z)",
            "Judul (Z-A)",
        ),
    )

class StatusFilter :
    Filter.Select<String>(
        "Status",
        arrayOf("Semua", "Ongoing", "Completed", "Hiatus", "Dropped"),
    )

class GenreFilter :
    Filter.Group<GenreTriState>(
        "Genre",
        GENRES.map { GenreTriState(it) },
    )

class GenreTriState(name: String) : Filter.TriState(name)

private val GENRES = listOf(
    "Action",
    "Adult",
    "Adventure",
    "Cheating",
    "Comedy",
    "Crossdressing",
    "Doujinshi",
    "Drama",
    "Ecchi",
    "Elf",
    "Erotica",
    "Fantasy",
    "Gore",
    "Harem",
    "Hentai",
    "Horror",
    "Incest",
    "Isekai",
    "Magic",
    "Mature",
    "Mecha",
    "Milf",
    "Mystery",
    "Psychological",
    "Reincarnation",
    "Romance",
    "School",
    "School Life",
    "Sci-Fi",
    "Seinen",
    "Sexual Violence",
    "Shota",
    "Shounen",
    "Slice of Life",
    "Smut",
    "Strategy Game",
    "Supernatural",
    "Survival",
    "Yandere",
    "Zombies",
)
