package no.oystein.bokhylle.data

import no.oystein.bokhylle.core.PageLayout

/** Bildefilene som hører til en side utover selve sidebildet. */
object PageImages {
    fun figurePaths(page: Page): List<String> =
        PageLayout.decode(page.layoutJson)?.blocks?.mapNotNull { it.image }.orEmpty()
}
