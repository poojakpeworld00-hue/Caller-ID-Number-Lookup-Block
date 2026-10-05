package io.launcher.home.views

import org.junit.Assert.assertEquals
import org.junit.Test

class OrphanWidgetViewsTest {

    /** A view whose row was deleted behind the grid's back (its app updated, say) is an orphan. */
    @Test
    fun viewWithoutItsRowIsOrphan() {
        val views = mapOf("search" to 7L, "time" to 3L)
        assertEquals(listOf("search"), HomeScreenGrid.orphanWidgetViews(views, liveItemIds = setOf(3L)))
    }

    /** A row still present keeps its view, whatever its widget id says mid-bind. */
    @Test
    fun viewWithLiveRowIsKept() {
        val views = mapOf("search" to 7L, "time" to 3L)
        assertEquals(emptyList<String>(), HomeScreenGrid.orphanWidgetViews(views, liveItemIds = setOf(3L, 7L)))
    }

    /**
     * First install: the row was bound (view tagged 42) but a refetch that read the database before
     * the new id was written hands back the row still at -1. Its view is still found, by row id.
     */
    @Test
    fun placedViewIsFoundByRowIdWhenWidgetIdIsStale() {
        val views = listOf("search" to (42 to 7L), "time" to (5 to 3L))
        val found = HomeScreenGrid.placedWidgetView(views, tagOf = { it.second.first }, itemIdOf = { it.second.second }, widgetId = -1, itemId = 7L)
        assertEquals("search", found?.first)
    }

    /** A view tied to no row is still found by its widget id, the way the grid always did. */
    @Test
    fun placedViewFallsBackToWidgetId() {
        val views = listOf("search" to (42 to null as Long?))
        val found = HomeScreenGrid.placedWidgetView(views, tagOf = { it.second.first }, itemIdOf = { it.second.second }, widgetId = 42, itemId = 7L)
        assertEquals("search", found?.first)
    }

    /** A view we never tied to a row is left alone rather than guessed at. */
    @Test
    fun untrackedViewIsKept() {
        val views = mapOf<String, Long?>("unknown" to null)
        assertEquals(emptyList<String>(), HomeScreenGrid.orphanWidgetViews(views, liveItemIds = emptySet()))
    }
}
