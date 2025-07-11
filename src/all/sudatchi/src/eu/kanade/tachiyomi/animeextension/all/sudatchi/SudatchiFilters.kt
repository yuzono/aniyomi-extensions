package eu.kanade.tachiyomi.animeextension.all.sudatchi

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import kotlinx.serialization.Serializable
import java.util.Calendar

object SudatchiFilters {

    interface QueryParameterFilter { fun toQueryParameter(): Pair<String, String?> }

    private class Checkbox(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    private class CheckboxList(name: String, private val paramName: String, private val pairs: List<Pair<String, String>>) :
        AnimeFilter.Group<AnimeFilter.CheckBox>(name, pairs.map { Checkbox(it.first) }), QueryParameterFilter {
        override fun toQueryParameter() = Pair(
            paramName,
            state.asSequence()
                .filter { it.state }
                .map { checkbox -> pairs.find { it.first == checkbox.name }!!.second }
                .filter(String::isNotBlank)
                .joinToString(","),
        )
    }

    fun getFilterList(): AnimeFilterList {
        val yearList = buildList {
            add(Pair("<select>", ""))
            addAll(
                (currentYear downTo 1960).map {
                    Pair(it.toString(), it.toString())
                },
            )
        }.toList()

        return AnimeFilterList(
            CheckboxList("Genres", "genres", genreList.toPairList()),
            CheckboxList("Status", "status", statusList.toPairList()),
            CheckboxList("Format", "format", formatList.toPairList()),
            CheckboxList("Year", "year", yearList),
            CheckboxList("Status", "sort", sortList.toPairList()),
        )
    }

    private fun List<FilterItemDto>.toPairList() = map { Pair(it.name, it.id) }

    private val currentYear by lazy {
        Calendar.getInstance().get(Calendar.YEAR)
    }

    @Serializable
    data class FilterItemDto(
        val id: String,
        val name: String,
    )

    private val genreList = listOf(
        FilterItemDto("Action", "Action"),
        FilterItemDto("Adventure", "Adventure"),
        FilterItemDto("Comedy", "Comedy"),
        FilterItemDto("Drama", "Drama"),
        FilterItemDto("Ecchi", "Ecchi"),
        FilterItemDto("Fantasy", "Fantasy"),
        FilterItemDto("Horror", "Horror"),
        FilterItemDto("Mahou Shoujo", "Mahou Shoujo"),
        FilterItemDto("Mecha", "Mecha"),
        FilterItemDto("Music", "Music"),
        FilterItemDto("Mystery", "Mystery"),
        FilterItemDto("Psychological", "Psychological"),
        FilterItemDto("Romance", "Romance"),
        FilterItemDto("Sci-Fi", "Sci-Fi"),
        FilterItemDto("Slice of Life", "Slice of Life"),
        FilterItemDto("Sports", "Sports"),
        FilterItemDto("Supernatural", "Supernatural"),
        FilterItemDto("Thriller", "Thriller"),
    )

    private val formatList = listOf(
        FilterItemDto("<select>", ""),
        FilterItemDto("TV", "TV Series"),
        FilterItemDto("MOVIE", "Movie"),
        FilterItemDto("OVA", "OVA"),
        FilterItemDto("ONA", "ONA"),
        FilterItemDto("TV_SHORT", "TV Short"),
        FilterItemDto("SPECIAL", "Special"),
    )

    private val statusList = listOf(
        FilterItemDto("<select>", ""),
        FilterItemDto("RELEASING", "Currently Airing"),
        FilterItemDto("FINISHED", "Completed"),
        FilterItemDto("NOT_YET_RELEASED", "Upcoming"),
        FilterItemDto("CANCELLED", "Cancelled"),
    )

    private val sortList = listOf(
        FilterItemDto("POPULARITY_DESC", "Most Popular"),
        FilterItemDto("SCORE_DESC", "Highest Rated"),
        FilterItemDto("TRENDING_DESC", "Trending"),
        FilterItemDto("START_DATE_DESC", "Newest"),
        FilterItemDto("TITLE_ROMAJI", "A-Z"),
        FilterItemDto("EPISODES_DESC", "Most Episodes"),
    )
}
