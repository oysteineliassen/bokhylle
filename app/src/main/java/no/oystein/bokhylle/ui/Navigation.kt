package no.oystein.bokhylle.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import no.oystein.bokhylle.ui.book.BookScreen
import no.oystein.bokhylle.ui.book.NewBookScreen
import no.oystein.bokhylle.ui.library.LibraryScreen
import no.oystein.bokhylle.ui.reader.ReaderScreen
import no.oystein.bokhylle.ui.search.SearchScreen
import no.oystein.bokhylle.ui.review.CropScreen
import no.oystein.bokhylle.ui.review.ReviewScreen
import no.oystein.bokhylle.ui.settings.SettingsScreen

object Routes {
    const val LIBRARY = "bibliotek"
    const val NEW_BOOK = "ny"
    fun book(id: Long) = "bok/$id"
    fun review(id: Long) = "kontroll/$id"
    fun crop(pageId: Long) = "beskjaer/$pageId"
    const val SETTINGS = "innstillinger"
    fun read(id: Long, pageId: Long? = null) = "les/$id" + (pageId?.let { "?side=$it" } ?: "")
    const val SEARCH = "sok"
}

@Composable
fun BokhylleNavHost(openBookId: Long? = null, onOpened: () -> Unit = {}) {
    val nav = rememberNavController()
    // Åpnet fra «Del → Bokhylle»: gå rett til boka.
    androidx.compose.runtime.LaunchedEffect(openBookId) {
        if (openBookId != null) {
            nav.navigate(Routes.book(openBookId)) { popUpTo(Routes.LIBRARY) }
            onOpened()
        }
    }
    NavHost(navController = nav, startDestination = Routes.LIBRARY) {
        composable(Routes.LIBRARY) {
            LibraryScreen(
                onOpenBook = { nav.navigate(Routes.book(it)) },
                onNewBook = { nav.navigate(Routes.NEW_BOOK) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onSearch = { nav.navigate(Routes.SEARCH) },
            )
        }
        composable(Routes.NEW_BOOK) {
            NewBookScreen(
                onBack = { nav.popBackStack() },
                onCreated = { id ->
                    nav.navigate(Routes.book(id)) { popUpTo(Routes.LIBRARY) }
                },
            )
        }
        composable("bok/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            BookScreen(
                bookId = entry.arguments!!.getLong("id"),
                onBack = { nav.popBackStack() },
                onReview = { nav.navigate(Routes.review(it)) },
                onRead = { nav.navigate(Routes.read(it)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable("kontroll/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            ReviewScreen(
                bookId = entry.arguments!!.getLong("id"),
                onBack = { nav.popBackStack() },
                onCrop = { nav.navigate(Routes.crop(it)) },
            )
        }
        composable(
            "les/{id}?side={side}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }, navArgument("side") { type = NavType.LongType; defaultValue = -1L }),
        ) { entry ->
            ReaderScreen(
                bookId = entry.arguments!!.getLong("id"),
                onBack = { nav.popBackStack() },
                startPageId = entry.arguments!!.getLong("side").takeIf { it >= 0 },
            )
        }
        composable(Routes.SEARCH) {
            SearchScreen(
                onBack = { nav.popBackStack() },
                onOpenBook = { nav.navigate(Routes.book(it)) },
                onOpenPage = { book, page -> nav.navigate(Routes.read(book, page)) },
            )
        }
        composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
        composable("beskjaer/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            CropScreen(pageId = entry.arguments!!.getLong("id"), onBack = { nav.popBackStack() })
        }
    }
}
