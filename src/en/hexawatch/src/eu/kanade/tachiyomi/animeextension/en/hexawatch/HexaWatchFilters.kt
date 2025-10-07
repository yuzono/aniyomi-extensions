package eu.kanade.tachiyomi.animeextension.en.hexawatch

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object HexaWatchFilters {

    class TypeFilter : AnimeFilter.Select<String>("Type", arrayOf("Movie", "TV Show"))

    class SortFilter : AnimeFilter.Sort(
        "Sort By",
        arrayOf("Popularity", "Rating", "Release Date"),
        Selection(0, false),
    )

    private class GenreCheckBox(name: String) : AnimeFilter.CheckBox(name)
    class GenreFilter(name: String, genres: Array<String>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, genres.map { GenreCheckBox(it) })

    // Combined list for the UI
    val ALL_GENRES = arrayOf(
        "Action", "Action & Adventure", "Adventure", "Animation", "Comedy", "Crime",
        "Documentary", "Drama", "Family", "Fantasy", "History", "Horror", "Kids",
        "Music", "Mystery", "News", "Reality", "Romance", "Sci-Fi & Fantasy",
        "Science Fiction", "Soap", "TV Movie", "Talk", "Thriller", "War",
        "War & Politics", "Western",
    ).sortedArray()

    // Separate maps for looking up the correct ID
    val MOVIE_GENRE_MAP = mapOf(
        "Action" to "28", "Adventure" to "12", "Animation" to "16", "Comedy" to "35",
        "Crime" to "80", "Documentary" to "99", "Drama" to "18", "Family" to "10751",
        "Fantasy" to "14", "History" to "36", "Horror" to "27", "Music" to "10402",
        "Mystery" to "9648", "Romance" to "10749", "Science Fiction" to "878",
        "TV Movie" to "10770", "Thriller" to "53", "War" to "10752", "Western" to "37",
    )

    val TV_GENRE_MAP = mapOf(
        "Action & Adventure" to "10759", "Animation" to "16", "Comedy" to "35",
        "Crime" to "80", "Documentary" to "99", "Drama" to "18", "Family" to "10751",
        "Kids" to "10762", "Mystery" to "9648", "News" to "10763", "Reality" to "10764",
        "Sci-Fi & Fantasy" to "10765", "Soap" to "10766", "Talk" to "10767",
        "War & Politics" to "10768", "Western" to "37",
    )

    // ========================== Watch Providers ==========================
    class WatchProviderCheckBox(name: String, val id: String) : AnimeFilter.CheckBox(name)
    class WatchProviderFilter : AnimeFilter.Group<WatchProviderCheckBox>(
        "Streaming Platforms",
        listOf(
            WatchProviderCheckBox("Netflix", "8"),
            WatchProviderCheckBox("Amazon Prime Video", "9"),
            WatchProviderCheckBox("Disney+", "337"),
            WatchProviderCheckBox("HBO Max", "384"),
            WatchProviderCheckBox("Apple TV+", "350"),
        ),
    )

    fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Use text search for global search"),
        AnimeFilter.Header("Filters only apply when text search is empty"),
        TypeFilter(),
        SortFilter(),
        GenreFilter("Genres", ALL_GENRES),
        WatchProviderFilter(),
    )
}
