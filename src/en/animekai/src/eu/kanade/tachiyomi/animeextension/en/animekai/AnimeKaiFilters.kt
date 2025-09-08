package eu.kanade.tachiyomi.animeextension.en.animekai

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnimeKaiFilters {

    open class QueryPartFilter(
        displayName: String,
        private val param: String,
        private val options: List<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        options.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart() = "$param[]=${options[state].second}"
    }

    open class CheckBoxFilterList(
        name: String,
        private val param: String,
        private val options: List<Pair<String, String>>,
    ) : AnimeFilter.Group<AnimeFilter.CheckBox>(
        name,
        options.map { CheckBoxVal(it.first, false) },
    ) {
        fun toQueryPart() = state
            .filter { it.state }
            .mapNotNull { checkbox ->
                options.find { it.first == checkbox.name }?.second?.let {
                    "$param[]=$it"
                }
            }
            .joinToString("&")
    }

    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    private inline fun <reified R> AnimeFilterList.asQueryPart(): String {
        return (this.getFirst<R>() as QueryPartFilter).toQueryPart()
    }

    private inline fun <reified R> AnimeFilterList.getFirst(): R {
        return this.filterIsInstance<R>().first()
    }

    private inline fun <reified R> AnimeFilterList.parseCheckbox(
        param: String,
        options: List<Pair<String, String>>,
    ): String {
        return (this.getFirst<R>() as CheckBoxFilterList).state
            .filter { it.state }
            .mapNotNull { checkbox ->
                options.find { it.first == checkbox.name }?.second?.let {
                    "$param[]=$it"
                }
            }
            .joinToString("&")
    }

    class TypesFilter : CheckBoxFilterList("Types", "type", AnimeKaiFiltersData.TYPES)
    class GenresFilter : CheckBoxFilterList("Genres", "genre", AnimeKaiFiltersData.GENRES)
    class StatusFilter : CheckBoxFilterList("Status", "status", AnimeKaiFiltersData.STATUS)

    class SortByFilter : QueryPartFilter("Sort By", "sort", AnimeKaiFiltersData.SORT_BY)

    class SeasonsFilter : CheckBoxFilterList("Season", "season", AnimeKaiFiltersData.SEASONS)
    class YearsFilter : CheckBoxFilterList("Year", "year", AnimeKaiFiltersData.YEARS)
    class RatingFilter : CheckBoxFilterList("Rating", "rating", AnimeKaiFiltersData.RATINGS)
    class CountriesFilter : CheckBoxFilterList("Origin Country", "country", AnimeKaiFiltersData.COUNTRIES)
    class LanguagesFilter : CheckBoxFilterList("Language", "language", AnimeKaiFiltersData.LANGUAGES)

    val FILTER_LIST get() = AnimeFilterList(
        TypesFilter(),
        GenresFilter(),
        StatusFilter(),
        SortByFilter(),
        SeasonsFilter(),
        YearsFilter(),
        RatingFilter(),
        CountriesFilter(),
        LanguagesFilter(),
    )

    data class FilterSearchParams(
        val types: String = "",
        val genres: String = "",
        val status: String = "",
        val sortBy: String = "",
        val seasons: String = "",
        val years: String = "",
        val rating: String = "",
        val countries: String = "",
        val languages: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            filters.getFirst<TypesFilter>().toQueryPart(),
            filters.getFirst<GenresFilter>().toQueryPart(),
            filters.getFirst<StatusFilter>().toQueryPart(),
            filters.getFirst<SortByFilter>().toQueryPart(),
            filters.getFirst<SeasonsFilter>().toQueryPart(),
            filters.getFirst<YearsFilter>().toQueryPart(),
            filters.getFirst<RatingFilter>().toQueryPart(),
            filters.getFirst<CountriesFilter>().toQueryPart(),
            filters.getFirst<LanguagesFilter>().toQueryPart(),
        )
    }

    private object AnimeKaiFiltersData {
        val ALL = Pair("All", "all")

        val COUNTRIES = listOf(
            Pair("China", "2"),
            Pair("Japan", "11"),
        )

        val LANGUAGES = listOf(
            Pair("Hard Sub", "sub"),
            Pair("Soft Sub", "softsub"),
            Pair("Dub", "dub"),
            Pair("Sub & Dub", "subdub"),
        )

        val SEASONS = listOf(
            ALL,
            Pair("Fall", "fall"),
            Pair("Summer", "summer"),
            Pair("Spring", "spring"),
            Pair("Winter", "winter"),
            Pair("Unknown", "unknown"),
        )

        val YEARS = listOf(
            ALL,
            Pair("2025", "2025"),
            Pair("2024", "2024"),
            Pair("2023", "2023"),
            Pair("2022", "2022"),
            Pair("2021", "2021"),
            Pair("2020", "2020"),
            Pair("2019", "2019"),
            Pair("2018", "2018"),
            Pair("2017", "2017"),
            Pair("2016", "2016"),
            Pair("2015", "2015"),
            Pair("2014", "2014"),
            Pair("2013", "2013"),
            Pair("2012", "2012"),
            Pair("2011", "2011"),
            Pair("2010", "2010"),
            Pair("2009", "2009"),
            Pair("2008", "2008"),
            Pair("2007", "2007"),
            Pair("2006", "2006"),
            Pair("2005", "2005"),
            Pair("2004", "2004"),
            Pair("2003", "2003"),
            Pair("2002", "2002"),
            Pair("2001", "2001"),
            Pair("2000", "2000"),
            Pair("1990s", "1990s"),
            Pair("1980s", "1980s"),
            Pair("1970s", "1970s"),
            Pair("1960s", "1960s"),
            Pair("1950s", "1950s"),
            Pair("1940s", "1940s"),
            Pair("1930s", "1930s"),
            Pair("1920s", "1920s"),
            Pair("1910s", "1910s"),
            Pair("1900s", "1900s"),
        )

        val SORT_BY = listOf(
            Pair("Update", "updated_date"),
        )

        val STATUS = listOf(
            Pair("Releasing", "releasing"),
            Pair("Completed", "completed"),
        )

        val TYPES = listOf(
            Pair("Movie", "movie"),
            Pair("TV", "tv"),
            Pair("OVA", "ova"),
            Pair("ONA", "ona"),
            Pair("Special", "special"),
            Pair("Music", "music"),
        )

        val RATINGS = listOf(
            Pair("G - All Ages", "g"),
            Pair("PG - Children", "pg"),
            Pair("PG-13 - Teens 13 or older", "pg_13"),
            Pair("R - 17+, Violence & Profanity", "r"),
            Pair("R+ - Profanity & Mild Nudity", "r+"),
            Pair("Rx - Hentai", "rx"),
        )

        val GENRES = listOf(
            Pair("Action", "Action"),
            Pair("Adventure", "Adventure"),
            Pair("Cars", "Cars"),
            Pair("Comedy", "Comedy"),
            Pair("Dementia", "Dementia"),
            Pair("Demons", "Demons"),
            Pair("Drama", "Drama"),
            Pair("Ecchi", "Ecchi"),
            Pair("Fantasy", "Fantasy"),
            Pair("Game", "Game"),
            Pair("Harem", "Harem"),
            Pair("Historical", "Historical"),
            Pair("Horror", "Horror"),
            Pair("Isekai", "Isekai"),
            Pair("Josei", "Josei"),
            Pair("Kids", "Kids"),
            Pair("Magic", "Magic"),
            Pair("Martial Arts", "Martial Arts"),
            Pair("Mecha", "Mecha"),
            Pair("Military", "Military"),
            Pair("Music", "Music"),
            Pair("Mystery", "Mystery"),
            Pair("Parody", "Parody"),
            Pair("Police", "Police"),
            Pair("Psychological", "Psychological"),
            Pair("Romance", "Romance"),
            Pair("Samurai", "Samurai"),
            Pair("School", "School"),
            Pair("Sci-Fi", "Sci-Fi"),
            Pair("Seinen", "Seinen"),
            Pair("Shoujo", "Shoujo"),
            Pair("Shoujo Ai", "Shoujo Ai"),
            Pair("Shounen", "Shounen"),
            Pair("Shounen Ai", "Shounen Ai"),
            Pair("Slice of Life", "Slice of Life"),
            Pair("Space", "Space"),
            Pair("Sports", "Sports"),
            Pair("Super Power", "Super Power"),
            Pair("Supernatural", "Supernatural"),
            Pair("Thriller", "Thriller"),
            Pair("Unknown", "Unknown"),
            Pair("Vampire", "Vampire"),
            Pair("Yaoi", "Yaoi"),
            Pair("Yuri", "Yuri"),
        )
    }
}
