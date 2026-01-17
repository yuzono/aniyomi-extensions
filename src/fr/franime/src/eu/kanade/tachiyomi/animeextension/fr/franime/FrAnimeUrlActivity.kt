package eu.kanade.tachiyomi.animeextension.fr.franime

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

class FrAnimeUrlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pathSegments = intent?.data?.pathSegments
        if (pathSegments != null && pathSegments.size >= 2) {
            val id = pathSegments[1]
            val mainIntent = Intent().apply {
                action = "eu.kanade.tachiyomi.ANIMESEARCH"
                putExtra("query", "${FrAnime.PREFIX_SEARCH}$id")
                putExtra("filter", packageName)
            }
            startActivity(mainIntent)
        } else {
            Log.e("FrAnimeUrlActivity", "Impossible de traiter le lien : ${intent?.data}")
        }
        finish()
        exitProcess(0)
    }
}
