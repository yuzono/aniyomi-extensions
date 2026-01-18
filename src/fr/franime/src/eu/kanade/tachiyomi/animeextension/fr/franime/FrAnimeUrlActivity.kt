package eu.kanade.tachiyomi.animeextension.fr.franime

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

/**
 * Springboard qui accepte les intents https://franime.fr/anime/<item>
 * et les redirige vers le processus principal d'Aniyomi.
 */
class FrAnimeUrlActivity : Activity() {

    private val tag = javaClass.simpleName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pathSegments = intent?.data?.pathSegments
        if (pathSegments != null && pathSegments.size > 1) {
            val item = pathSegments[1]
            val mainIntent = Intent().apply {
                action = "eu.kanade.tachiyomi.ANIMESEARCH"
                putExtra("query", "${FrAnime.PREFIX_SEARCH}$item")
                putExtra("filter", packageName)
            }

            try {
                startActivity(mainIntent)
            } catch (e: ActivityNotFoundException) {
                Log.e(tag, e.toString())
            }
        } else {
            Log.e(tag, "Impossible d'analyser l'URI de l'intent $intent")
        }

        finish()
        exitProcess(0)
    }
}
