package no.oystein.bokhylle.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import no.oystein.bokhylle.ui.backup.ImportScreen
import no.oystein.bokhylle.ui.book.BookScreen
import no.oystein.bokhylle.ui.book.FigureCompareScreen
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
    fun figures(id: Long) = "figurer/$id"
    const val SETTINGS = "innstillinger"
    fun read(id: Long, pageId: Long? = null) = "les/$id" + (pageId?.let { "?side=$it" } ?: "")
    const val SEARCH = "sok"
    fun import(path: String, undo: Boolean = false) = "importer?fil=${android.net.Uri.encode(path)}&angre=$undo"
}

@Composable
fun BokhylleNavHost(openBookId: Long? = null, importFile: String? = null, onOpened: () -> Unit = {}) {
    val nav = rememberNavController()
    // Åpnet fra «Del → Bokhylle» eller en .bokhylle-fil: gå rett til boka eller importskjermen.
    androidx.compose.runtime.LaunchedEffect(openBookId, importFile) {
        if (openBookId != null) {
            nav.navigate(Routes.book(openBookId)) { popUpTo(Routes.LIBRARY) }
            onOpened()
        } else if (importFile != null) {
            nav.navigate(Routes.import(importFile)) { popUpTo(Routes.LIBRARY) }
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
                onCompareFigures = { nav.navigate(Routes.figures(it)) },
            )
        }
        composable("figurer/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            FigureCompareScreen(bookId = entry.arguments!!.getLong("id"), onBack = { nav.popBackStack() })
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
        composable(
            "importer?fil={fil}&angre={angre}",
            arguments = listOf(
                navArgument("fil") { type = NavType.StringType; defaultValue = "" },
                navArgument("angre") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            ImportScreen(
                path = entry.arguments!!.getString("fil").orEmpty(),
                undo = entry.arguments!!.getBoolean("angre"),
                onBack = { nav.popBackStack() },
                onDone = { bookId ->
                    if (bookId != null) nav.navigate(Routes.book(bookId)) { popUpTo(Routes.LIBRARY) }
                    else nav.popBackStack(Routes.LIBRARY, inclusive = false)
                },
            )
        }
        composable(Routes.SEARCH) {
            SearchScreen(
                onBack = { nav.popBackStack() },
                onOpenBook = { nav.navigate(Routes.book(it)) },
                onOpenPage = { book, page -> nav.navigate(Routes.read(book, page)) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() }, onImport = { path, undo -> nav.navigate(Routes.import(path, undo)) })
        }
        composable("beskjaer/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            CropScreen(pageId = entry.arguments!!.getLong("id"), onBack = { nav.popBackStack() })
        }
    }
}
