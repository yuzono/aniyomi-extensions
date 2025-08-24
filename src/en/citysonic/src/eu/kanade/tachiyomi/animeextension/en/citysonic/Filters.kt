package eu.kanade.tachiyomi.animeextension.en.citysonic

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import extensions.utils.firstInstanceOrNull

object Filters {

    open class SelectFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart() = vals[state].second
    }

    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)
    open class CheckBoxFilterList(name: String, values: List<CheckBox>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, values)

    class TypeFilter : SelectFilter("Type", FiltersData.TYPES)
    class QualityFilter : SelectFilter("Quality", FiltersData.QUALITIES)
    class ReleasedFilter : SelectFilter("Released", FiltersData.YEARS)

    class GenresFilter : CheckBoxFilterList(
        "Genres",
        FiltersData.GENRES.map { CheckBoxVal(it.first, false) },
    )

    class CountriesFilter : CheckBoxFilterList(
        "Countries",
        FiltersData.COUNTRIES.map { CheckBoxVal(it.first, false) },
    )

    val FILTER_LIST get() = AnimeFilterList(
        GenresFilter(),
        CountriesFilter(),

        TypeFilter(),
        QualityFilter(),
        ReleasedFilter(),
    )

    data class FilterSearchParams(
        val genres: String = "",
        val countries: String = "",
        val type: String = "",
        val quality: String = "",
        val released: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        val genres: String = filters.filterIsInstance<GenresFilter>()
            .first()
            .state.mapNotNull { format ->
                if (format.state) {
                    FiltersData.GENRES.find { it.first == format.name }!!.second
                } else { null }
            }.joinToString("-")

        val countries: String = filters.filterIsInstance<CountriesFilter>()
            .first()
            .state.mapNotNull { format ->
                if (format.state) {
                    FiltersData.COUNTRIES.find { it.first == format.name }!!.second
                } else { null }
            }.joinToString("-")

        return FilterSearchParams(
            genres = genres,
            countries = countries,
            type = filters.firstInstanceOrNull<TypeFilter>()?.toQueryPart() ?: "",
            quality = filters.firstInstanceOrNull<QualityFilter>()?.toQueryPart() ?: "",
            released = filters.firstInstanceOrNull<ReleasedFilter>()?.toQueryPart() ?: "",
        )
    }

    private object FiltersData {

        val ALL = Pair("All", "all")

        val TYPES = arrayOf(
            ALL,
            Pair("Movie", "movie"),
            Pair("TV-series", "tv"),
        )

        val QUALITIES = arrayOf(
            ALL,
            Pair("HD", "hd"),
            Pair("SD", "sd"),
            Pair("CAM", "cam"),
        )

        val COUNTRIES = arrayOf(
            ALL,
            "Argentina" to "11",
            "Australia" to "151",
            "Austria" to "4",
            "Belgium" to "44",
            "Brazil" to "190",
            "Canada" to "147",
            "China" to "101",
            "Czech Republic" to "231",
            "Denmark" to "222",
            "Finland" to "158",
            "France" to "3",
            "Germany" to "96",
            "Hong Kong" to "93",
            "Hungary" to "72",
            "India" to "105",
            "Ireland" to "196",
            "Israel" to "24",
            "Italy" to "205",
            "Japan" to "173",
            "Luxembourg" to "91",
            "Mexico" to "40",
            "Netherlands" to "172",
            "New Zealand" to "122",
            "Norway" to "219",
            "Poland" to "23",
            "Romania" to "170",
            "Russia" to "109",
            "South Africa" to "200",
            "South Korea" to "135",
            "Spain" to "62",
            "Sweden" to "114",
            "Switzerland" to "41",
            "Taiwan" to "119",
            "Thailand" to "57",
            "United Kingdom" to "180",
            "United States of America" to "129",
        )

        val YEARS = arrayOf(ALL) + (1917..2025).map {
            Pair(it.toString(), it.toString())
        }.reversed().toTypedArray()

        val GENRES = arrayOf(
            Pair("Action", "1"),
            Pair("Adventure", "2"),
            Pair("Cars", "3"),
            Pair("Comedy", "4"),
            Pair("Dementia", "5"),
            Pair("Demons", "6"),
            Pair("Drama", "8"),
            Pair("Ecchi", "9"),
            Pair("Fantasy", "10"),
            Pair("Game", "11"),
            Pair("Harem", "35"),
            Pair("Historical", "13"),
            Pair("Horror", "14"),
            Pair("Isekai", "44"),
            Pair("Josei", "43"),
            Pair("Kids", "15"),
            Pair("Magic", "16"),
            Pair("Martial Arts", "17"),
            Pair("Mecha", "18"),
            Pair("Military", "38"),
            Pair("Music", "19"),
            Pair("Mystery", "7"),
            Pair("Parody", "20"),
            Pair("Police", "39"),
            Pair("Psychological", "40"),
            Pair("Romance", "22"),
            Pair("Samurai", "21"),
            Pair("School", "23"),
            Pair("Sci-Fi", "24"),
            Pair("Seinen", "42"),
            Pair("Shoujo", "25"),
            Pair("Shoujo Ai", "26"),
            Pair("Shounen", "27"),
            Pair("Shounen Ai", "28"),
            Pair("Slice of Life", "36"),
            Pair("Space", "29"),
            Pair("Sports", "30"),
            Pair("Super Power", "31"),
            Pair("Supernatural", "37"),
            Pair("Thriller", "41"),
            Pair("Vampire", "32"),
            Pair("Yaoi", "33"),
            Pair("Yuri", "34"),
        )
    }
}
