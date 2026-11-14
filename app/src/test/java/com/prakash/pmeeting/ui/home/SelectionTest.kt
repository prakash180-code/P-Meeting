package com.prakash.pmeeting.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Invert Selection must only ever touch the meetings the user can currently
 * see: filtered-out search results and archived meetings are never selected.
 */
class SelectionTest {

    @Test
    fun invertSelection_swapsSelectedAndUnselected() {
        val visible = setOf(1L, 2L, 3L, 4L)
        val selected = setOf(1L, 3L)

        assertEquals(setOf(2L, 4L), invertedSelection(visible, selected))
    }

    @Test
    fun invertSelection_onlyTouchesVisibleIds() {
        val visible = setOf(2L, 4L)
        // 9L was selected earlier but is no longer visible (filter/archive).
        val selected = setOf(2L, 9L)

        assertEquals(setOf(4L), invertedSelection(visible, selected))
    }

    @Test
    fun invertSelection_withFilteredList() {
        // Search for "budget" matches 10 and 12; selecting both then inverting
        // must yield an empty selection *within the filter*, not select others.
        val filteredVisible = setOf(10L, 12L)
        assertEquals(emptySet<Long>(), invertedSelection(filteredVisible, setOf(10L, 12L)))
        assertEquals(setOf(10L, 12L), invertedSelection(filteredVisible, emptySet()))
    }

    @Test
    fun invertSelection_isItsOwnInverse() {
        val visible = setOf(1L, 2L, 3L)
        val once = invertedSelection(visible, setOf(2L))
        val twice = invertedSelection(visible, once)

        assertEquals(setOf(2L), twice)
    }

    @Test
    fun invertSelection_emptyVisibleListDoesNothing() {
        assertEquals(emptySet<Long>(), invertedSelection(emptySet(), setOf(1L, 2L)))
    }
}
