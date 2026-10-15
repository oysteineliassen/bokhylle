package no.oystein.bokhylle.data

import no.oystein.bokhylle.core.PageLayout

/** Bildefilene som hører til en side utover selve sidebildet. */
object PageImages {
    fun figurePaths(page: Page): List<String> =
        PageLayout.decode(page.layoutJson)?.blocks?.flatMap { listOfNotNull(it.image, it.photo) }.orEmpty()

    /** Figuren er tegnet på nytt (se ClaudeDraw); filnavnet skiller tegninger fra utklipte foto. */
    fun isDrawing(path: String?) = path != null && java.io.File(path).name.startsWith(DRAWING_PREFIX)

    const val DRAWING_PREFIX = "tegning-"
}
