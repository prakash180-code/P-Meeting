package com.prakash.pmeeting.ui.home

/**
 * Invert selection within the currently visible list only.
 *
 * Meetings that are filtered out (search) or not part of the current view
 * (archived vs active) can never be selected by inversion, and previously
 * selected entries that are no longer visible are dropped, so bulk actions
 * only ever touch what the user can see.
 */
internal fun invertedSelection(visibleIds: Set<Long>, selectedIds: Set<Long>): Set<Long> =
    visibleIds - selectedIds
