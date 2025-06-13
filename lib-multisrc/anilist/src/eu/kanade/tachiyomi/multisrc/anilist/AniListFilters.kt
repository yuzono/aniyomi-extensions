package eu.kanade.tachiyomi.multisrc.anilist

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilter.TriState
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import java.util.Calendar

object AniListFilters {
    open class QueryPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart() = vals[state].second
    }

    open class TriStateFilterList(name: String, pairs: List<String>) :
        AnimeFilter.Group<TriState>(name, pairs.map { TriFilter(it) })

    class TriFilter(name: String) : TriState(name)

    open class CheckBoxFilterList(name: String, pairs: Array<Pair<String, String>>) :
        AnimeFilter.Group<AnimeFilter.CheckBox>(name, pairs.map { CheckBoxVal(it.first, false) })

    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    private inline fun <reified R> AnimeFilterList.asQueryPart(): String {
        return (getFirst<R>() as QueryPartFilter).toQueryPart()
    }

    private inline fun <reified R> AnimeFilterList.getFirst(): R {
        return first { it is R } as R
    }

    private inline fun <reified R> AnimeFilterList.parseCheckboxList(
        options: Array<Pair<String, String>>,
    ): List<String> {
        return (getFirst<R>() as CheckBoxFilterList).state
            .filter { it.state }
            .map { checkBox -> options.find { it.first == checkBox.name }!!.second }
            .filter(String::isNotBlank)
    }

    private inline fun <reified R> AnimeFilterList.parseTriFilter(): List<List<String>> {
        return (first { it is R } as TriStateFilterList).state
            .filterNot { it.isIgnored() }
            .map { filter -> filter.state to filter.name }
            .groupBy { it.first } // group by state
            .let { dict ->
                val included = dict[TriState.STATE_INCLUDE]?.map { it.second }.orEmpty()
                val excluded = dict[TriState.STATE_EXCLUDE]?.map { it.second }.orEmpty()
                listOf(included, excluded)
            }
    }

    private inline fun <reified R> AnimeFilterList.getSort(): String {
        val state = (getFirst<R>() as AnimeFilter.Sort).state ?: return ""
        val index = state.index
        val suffix = if (state.ascending) "" else "_DESC"
        return AniListFiltersData.SORT_LIST[index].second + suffix
    }

    class GenreFilter : TriStateFilterList("Genres", AniListFiltersData.GENRE_LIST)
    class TagFilter : TriStateFilterList("Tags", AniListFiltersData.TAG_LIST)
    class YearFilter : QueryPartFilter("Year", AniListFiltersData.YEAR_LIST)
    class SeasonFilter : QueryPartFilter("Season", AniListFiltersData.SEASON_LIST)
    class FormatFilter : CheckBoxFilterList("Format", AniListFiltersData.FORMAT_LIST)
    class StatusFilter : QueryPartFilter("Airing Status", AniListFiltersData.STATUS_LIST)
    class CountryFilter(vals: Array<Pair<String, String>> = AniListFiltersData.COUNTRY_LIST) : QueryPartFilter("Country Of Origin", vals)

    class SortFilter : AnimeFilter.Sort(
        "Sort",
        AniListFiltersData.SORT_LIST.map { it.first }.toTypedArray(),
        Selection(1, false),
    )

    val FILTER_LIST get() = AnimeFilterList(
        GenreFilter(),
        TagFilter(),
        YearFilter(),
        SeasonFilter(),
        FormatFilter(),
        StatusFilter(),
        CountryFilter(),
        SortFilter(),
    )

    class FilterSearchParams(
        val genres: List<List<String>> = emptyList(),
        val tags: List<List<String>> = emptyList(),
        val year: String = "",
        val season: String = "",
        val format: List<String> = emptyList(),
        val status: String = "",
        val country: String = "",
        val sort: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        return FilterSearchParams(
            filters.parseTriFilter<GenreFilter>(),
            filters.parseTriFilter<TagFilter>(),
            filters.asQueryPart<YearFilter>(),
            filters.asQueryPart<SeasonFilter>(),
            filters.parseCheckboxList<FormatFilter>(AniListFiltersData.FORMAT_LIST),
            filters.asQueryPart<StatusFilter>(),
            filters.asQueryPart<CountryFilter>(),
            filters.getSort<SortFilter>(),
        )
    }

    private object AniListFiltersData {
        val GENRE_LIST = listOf(
            "Action",
            "Adventure",
            "Comedy",
            "Drama",
            "Ecchi",
            "Fantasy",
            "Horror",
            "Mahou Shoujo",
            "Mecha",
            "Music",
            "Mystery",
            "Psychological",
            "Romance",
            "Sci-Fi",
            "Slice of Life",
            "Sports",
            "Supernatural",
            "Thriller",
        )

        private val CURRENT_YEAR by lazy {
            Calendar.getInstance().get(Calendar.YEAR)
        }

        val YEAR_LIST = buildList {
            add(Pair("<Select>", ""))
            addAll(
                (CURRENT_YEAR + 1 downTo 1940).map {
                    Pair(it.toString(), it.toString())
                },
            )
        }.toTypedArray()

        val SEASON_LIST = arrayOf(
            Pair("<Select>", ""),
            Pair("Winter", "WINTER"),
            Pair("Spring", "SPRING"),
            Pair("Summer", "SUMMER"),
            Pair("Fall", "FALL"),
        )

        val FORMAT_LIST = arrayOf(
            Pair("TV Show", "TV"),
            Pair("Movie", "MOVIE"),
            Pair("TV Short", "TV_SHORT"),
            Pair("Special", "SPECIAL"),
            Pair("OVA", "OVA"),
            Pair("ONA", "ONA"),
            Pair("Music", "MUSIC"),
        )

        val STATUS_LIST = arrayOf(
            Pair("<Select>", ""),
            Pair("Airing", "RELEASING"),
            Pair("Finished", "FINISHED"),
            Pair("Not Yet Aired", "NOT_YET_RELEASED"),
            Pair("Cancelled", "CANCELLED"),
        )

        val COUNTRY_LIST = arrayOf(
            Pair("<Select>", ""),
            Pair("Japan", "JP"),
            Pair("South Korea", "KR"),
            Pair("China", "CN"),
            Pair("Taiwan", "TW"),
        )

        val SORT_LIST = arrayOf(
            Pair("Title", "TITLE_ENGLISH"),
            Pair("Popularity", "POPULARITY"),
            Pair("Average Score", "SCORE"),
            Pair("Trending", "TRENDING"),
            Pair("Favorites", "FAVOURITES"),
            Pair("Date Added", "ID"),
            Pair("Release Date", "START_DATE"),
            Pair("Search Match", "SEARCH_MATCH"),
        )

        val TAG_LIST = listOf(
            "4-koma",
            "Achromatic",
            "Achronological Order",
            "Acrobatics",
            "Acting",
            "Adoption",
            "Advertisement",
            "Afterlife",
            "Age Gap",
            "Age Regression",
            "Agender",
            "Agriculture",
            "Airsoft",
            "Alchemy",
            "Aliens",
            "Alternate Universe",
            "American Football",
            "Amnesia",
            "Anachronism",
            "Ancient China",
            "Angels",
            "Animals",
            "Anthology",
            "Anthropomorphism",
            "Anti-Hero",
            "Archery",
            "Aromantic",
            "Arranged Marriage",
            "Artificial Intelligence",
            "Asexual",
            "Assassins",
            "Astronomy",
            "Athletics",
            "Augmented Reality",
            "Autobiographical",
            "Aviation",
            "Badminton",
            "Band",
            "Bar",
            "Baseball",
            "Basketball",
            "Battle Royale",
            "Biographical",
            "Bisexual",
            "Blackmail",
            "Board Game",
            "Boarding School",
            "Body Horror",
            "Body Image",
            "Body Swapping",
            "Bowling",
            "Boxing",
            "Boys' Love",
            "Bullying",
            "Butler",
            "Calligraphy",
            "Camping",
            "Cannibalism",
            "Card Battle",
            "Cars",
            "Centaur",
            "CGI",
            "Cheerleading",
            "Chibi",
            "Chimera",
            "Chuunibyou",
            "Circus",
            "Class Struggle",
            "Classic Literature",
            "Classical Music",
            "Clone",
            "Coastal",
            "Cohabitation",
            "College",
            "Coming of Age",
            "Conspiracy",
            "Cosmic Horror",
            "Cosplay",
            "Cowboys",
            "Creature Taming",
            "Crime",
            "Criminal Organization",
            "Crossdressing",
            "Crossover",
            "Cult",
            "Cultivation",
            "Curses",
            "Cute Boys Doing Cute Things",
            "Cute Girls Doing Cute Things",
            "Cyberpunk",
            "Cyborg",
            "Cycling",
            "Dancing",
            "Death Game",
            "Delinquents",
            "Demons",
            "Denpa",
            "Desert",
            "Detective",
            "Dinosaurs",
            "Disability",
            "Dissociative Identities",
            "Dragons",
            "Drawing",
            "Drugs",
            "Dullahan",
            "Dungeon",
            "Dystopian",
            "E-Sports",
            "Eco-Horror",
            "Economics",
            "Educational",
            "Elderly Protagonist",
            "Elf",
            "Ensemble Cast",
            "Environmental",
            "Episodic",
            "Ero Guro",
            "Espionage",
            "Estranged Family",
            "Exorcism",
            "Fairy",
            "Fairy Tale",
            "Fake Relationship",
            "Family Life",
            "Fashion",
            "Female Harem",
            "Female Protagonist",
            "Femboy",
            "Fencing",
            "Filmmaking",
            "Firefighters",
            "Fishing",
            "Fitness",
            "Flash",
            "Food",
            "Football",
            "Foreign",
            "Found Family",
            "Fugitive",
            "Full CGI",
            "Full Color",
            "Gambling",
            "Gangs",
            "Gender Bending",
            "Ghost",
            "Go",
            "Goblin",
            "Gods",
            "Golf",
            "Gore",
            "Guns",
            "Gyaru",
            "Handball",
            "Henshin",
            "Heterosexual",
            "Hikikomori",
            "Hip-hop Music",
            "Historical",
            "Homeless",
            "Horticulture",
            "Ice Skating",
            "Idol",
            "Indigenous Cultures",
            "Inn",
            "Isekai",
            "Iyashikei",
            "Jazz Music",
            "Josei",
            "Judo",
            "Kaiju",
            "Karuta",
            "Kemonomimi",
            "Kids",
            "Kingdom Management",
            "Konbini",
            "Kuudere",
            "Lacrosse",
            "Language Barrier",
            "LGBTQ+ Themes",
            "Long Strip",
            "Lost Civilization",
            "Love Triangle",
            "Mafia",
            "Magic",
            "Mahjong",
            "Maids",
            "Makeup",
            "Male Harem",
            "Male Protagonist",
            "Marriage",
            "Martial Arts",
            "Matchmaking",
            "Matriarchy",
            "Medicine",
            "Medieval",
            "Memory Manipulation",
            "Mermaid",
            "Meta",
            "Metal Music",
            "Military",
            "Mixed Gender Harem",
            "Mixed Media",
            "Monster Boy",
            "Monster Girl",
            "Mopeds",
            "Motorcycles",
            "Mountaineering",
            "Musical Theater",
            "Mythology",
            "Natural Disaster",
            "Necromancy",
            "Nekomimi",
            "Ninja",
            "No Dialogue",
            "Noir",
            "Non-fiction",
            "Nudity",
            "Nun",
            "Office",
            "Office Lady",
            "Oiran",
            "Ojou-sama",
            "Orphan",
            "Otaku Culture",
            "Outdoor Activities",
            "Pandemic",
            "Parenthood",
            "Parkour",
            "Parody",
            "Philosophy",
            "Photography",
            "Pirates",
            "Poker",
            "Police",
            "Politics",
            "Polyamorous",
            "Post-Apocalyptic",
            "POV",
            "Pregnancy",
            "Primarily Adult Cast",
            "Primarily Animal Cast",
            "Primarily Child Cast",
            "Primarily Female Cast",
            "Primarily Male Cast",
            "Primarily Teen Cast",
            "Prison",
            "Proxy Battle",
            "Psychosexual",
            "Puppetry",
            "Rakugo",
            "Real Robot",
            "Rehabilitation",
            "Reincarnation",
            "Religion",
            "Rescue",
            "Restaurant",
            "Revenge",
            "Robots",
            "Rock Music",
            "Rotoscoping",
            "Royal Affairs",
            "Rugby",
            "Rural",
            "Samurai",
            "Satire",
            "School",
            "School Club",
            "Scuba Diving",
            "Seinen",
            "Shapeshifting",
            "Ships",
            "Shogi",
            "Shoujo",
            "Shounen",
            "Shrine Maiden",
            "Skateboarding",
            "Skeleton",
            "Slapstick",
            "Slavery",
            "Snowscape",
            "Software Development",
            "Space",
            "Space Opera",
            "Spearplay",
            "Steampunk",
            "Stop Motion",
            "Succubus",
            "Suicide",
            "Sumo",
            "Super Power",
            "Super Robot",
            "Superhero",
            "Surfing",
            "Surreal Comedy",
            "Survival",
            "Swimming",
            "Swordplay",
            "Table Tennis",
            "Tanks",
            "Tanned Skin",
            "Teacher",
            "Teens' Love",
            "Tennis",
            "Terrorism",
            "Time Loop",
            "Time Manipulation",
            "Time Skip",
            "Tokusatsu",
            "Tomboy",
            "Torture",
            "Tragedy",
            "Trains",
            "Transgender",
            "Travel",
            "Triads",
            "Tsundere",
            "Twins",
            "Unrequited Love",
            "Urban",
            "Urban Fantasy",
            "Vampire",
            "Vertical Video",
            "Veterinarian",
            "Video Games",
            "Vikings",
            "Villainess",
            "Virtual World",
            "Vocal Synth",
            "Volleyball",
            "VTuber",
            "War",
            "Werewolf",
            "Wilderness",
            "Witch",
            "Work",
            "Wrestling",
            "Writing",
            "Wuxia",
            "Yakuza",
            "Yandere",
            "Youkai",
            "Yuri",
            "Zombie",
        )
    }
}
