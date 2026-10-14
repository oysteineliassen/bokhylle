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

object Routes {
    const val LIBRARY = "bibliotek"
    const val NEW_BOOK = "ny"
    fun book(id: Long) = "bok/$id"
}

@Composable
fun BokhylleNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.LIBRARY) {
        composable(Routes.LIBRARY) {
            LibraryScreen(
                onOpenBook = { nav.navigate(Routes.book(it)) },
                onNewBook = { nav.navigate(Routes.NEW_BOOK) },
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
            )
        }
    }
}
