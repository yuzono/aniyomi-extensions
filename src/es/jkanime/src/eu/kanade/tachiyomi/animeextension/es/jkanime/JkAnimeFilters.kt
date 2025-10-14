package eu.kanade.tachiyomi.animeextension.es.jkanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

interface UriPartFilterInterface {
    fun toUriPart(): String
}

open class SelectFilter(
    displayName: String,
    val vals: Array<Pair<String, String>>,
) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {

    fun toValue() = vals[state].second
}

open class UriPartSelectFilter(
    displayName: String,
    val keyName: String,
    vals: Array<Pair<String, String>>,
    val includeZero: Boolean = false,
) : UriPartFilterInterface, SelectFilter(displayName, vals) {

    override fun toUriPart(): String {
        val value = toValue()
        return if ((includeZero || state != 0) && value.isNotBlank()) "$keyName=$value" else ""
    }
}

open class UriPartTextFilter(
    displayName: String,
    val keyName: String,
) : UriPartFilterInterface,
    AnimeFilter.Text(displayName) {
    override fun toUriPart() = if (state.isNotBlank()) "$keyName=$state" else ""
}

class GenreFilter : UriPartSelectFilter(
    "Géneros",
    "genero",
    arrayOf(
        Pair("<Seleccionar>", "none"),
        Pair("Español Latino", "espaol-latino"),
        Pair("Accion", "accion"),
        Pair("Aventura", "aventura"),
        Pair("Autos", "autos"),
        Pair("Comedia", "comedia"),
        Pair("Dementia", "dementia"),
        Pair("Demonios", "demonios"),
        Pair("Misterio", "misterio"),
        Pair("Drama", "drama"),
        Pair("Ecchi", "ecchi"),
        Pair("Fantasia", "fantasa"),
        Pair("Juegos", "juegos"),
        Pair("Hentai", "hentai"),
        Pair("Historico", "historico"),
        Pair("Terror", "terror"),
        Pair("Niños", "nios"),
        Pair("Magia", "magia"),
        Pair("Artes Marciales", "artes-marciales"),
        Pair("Mecha", "mecha"),
        Pair("Musica", "musica"),
        Pair("Parodia", "parodia"),
        Pair("Samurai", "samurai"),
        Pair("Romance", "romance"),
        Pair("Colegial", "colegial"),
        Pair("Sci-Fi", "sci-fi"),
        Pair("Shoujo", "shoujo"),
        Pair("Shoujo Ai", "shoujo-ai"),
        Pair("Shounen", "shounen"),
        Pair("Shounen Ai", "shounen-ai"),
        Pair("Space", "space"),
        Pair("Deportes", "deportes"),
        Pair("Super Poderes", "super-poderes"),
        Pair("Vampiros", "vampiros"),
        Pair("Yaoi", "yaoi"),
        Pair("Yuri", "yuri"),
        Pair("Harem", "harem"),
        Pair("Cosas de la vida", "cosas-de-la-vida"),
        Pair("Sobrenatural", "sobrenatural"),
        Pair("Militar", "militar"),
        Pair("Policial", "policial"),
        Pair("Psicologico", "psicologico"),
        Pair("Thriller", "thriller"),
        Pair("Seinen", "seinen"),
        Pair("Josei", "josei"),
        Pair("Isekai", "isekai"),
    ),
)

class DayFilter : SelectFilter(
    "Dia de emisión",
    arrayOf(
        Pair("<Seleccionar>", ""),
        Pair("Lunes", "Lunes"),
        Pair("Martes", "Martes"),
        Pair("Miércoles", "Miércoles"),
        Pair("Jueves", "Jueves"),
        Pair("Viernes", "Viernes"),
        Pair("Sábado", "Sábado"),
        Pair("Domingo", "Domingo"),
    ),
)

class TypeFilter : UriPartSelectFilter(
    "Tipo",
    "tipo",
    arrayOf(
        Pair("<Seleccionar>", ""),
        Pair("Animes", "animes"),
        Pair("Películas", "peliculas"),
        Pair("Especiales", "especiales"),
        Pair("OVAS", "ovas"),
        Pair("ONAS", "onas"),
    ),
)

class StateFilter : UriPartSelectFilter(
    "Estado",
    "estado",
    arrayOf(
        Pair("<Cualquiera>", ""),
        Pair("En emisión", "emision"),
        Pair("Finalizado", "finalizados"),
        Pair("Por Estrenar", "estrenos"),
    ),
)

class SeasonFilter : UriPartSelectFilter(
    "Temporada",
    "temporada",
    arrayOf(
        Pair("<Cualquiera>", ""),
        Pair("Primavera", "primavera"),
        Pair("Verano", "verano"),
        Pair("Otoño", "otoño"),
        Pair("Invierno", "invierno"),
    ),
)

class OrderByFilter : UriPartSelectFilter(
    "Ordenar por",
    "filtro",
    arrayOf(
        Pair("Por fecha", ""), // Por fecha no
        Pair("Por nombre", "nombre"),
        Pair("Por popularidad", "popularidad"),
    ),
)

class SortModifiers : UriPartSelectFilter(
    "De forma",
    "orden",
    arrayOf(
        Pair("Descendente", "desc"),
        Pair("Ascendente", "asc"),
    ),
    includeZero = true,
)

class YearFilter : UriPartTextFilter("Año", "fecha")
