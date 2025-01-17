/*
 * This file is part of Seeneva Android Reader
 * Copyright (C) 2021 Sergei Solodovnikov
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package app.seeneva.reader.screen.list

import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.whenStarted
import app.seeneva.reader.common.coroutines.Dispatchers
import app.seeneva.reader.extension.observe
import app.seeneva.reader.logic.ComicListViewType
import app.seeneva.reader.logic.ComicsSettings
import app.seeneva.reader.logic.comic.AddComicBookMode
import app.seeneva.reader.logic.comic.Library
import app.seeneva.reader.logic.entity.query.QueryParams
import app.seeneva.reader.logic.entity.query.QuerySort
import app.seeneva.reader.logic.entity.query.filter.Filter
import app.seeneva.reader.logic.entity.query.filter.FilterGroup
import app.seeneva.reader.presenter.BaseStatefulPresenter
import app.seeneva.reader.presenter.Presenter
import app.seeneva.reader.screen.list.entity.FilterLabel
import kotlinx.coroutines.launch
import org.tinylog.kotlin.Logger
import app.seeneva.reader.screen.list.ComicsListView.ScreenState as ViewScreenState

interface ComicsListPresenter : Presenter {
    val currentSearchQuery: String?

    /**
     * User click on edit list filters button
     */
    fun onEditFilterClick()

    /**
     * User click on sync button
     */
    fun onSyncClick()

    /**
     * Delete or mark as deleted
     * @param ids ids of comic book
     * @param permanent true - delete, false - mark as removed
     */
    fun deleteComicBook(ids: Set<Long>, permanent: Boolean = false)

    /**
     * Undo mark as removed
     * @param ids ids of comic book
     */
    fun undoComicRemove(ids: Set<Long>)

    /**
     * On sort comic book list button click
     */
    fun onSortListClick()

    /**
     * User selected a new sort
     * @param selectedSort buildNew sort type
     */
    fun onSortSelected(selectedSort: QuerySort)

    fun onFiltersAccepted(acceptedFilters: Map<FilterGroup.ID, Filter>)

    fun removeFilter(groupId: FilterGroup.ID)

    /**
     * Rename comic book
     * @param id ikd of the comic book to rename
     * @param title buildNew title of the comic book
     */
    fun renameComicBook(id: Long, title: String)

    fun onSearchQuery(query: String?)

    fun toggleComicCompletedMark(id: Long)

    /**
     * Set completed mark to all comics with provided [ids]
     * @param ids comic books ids
     * @param completed which flag should be set
     */
    fun setComicsCompletedMark(ids: Set<Long>, completed: Boolean)

    fun onListTypeChanged(listType: ComicListViewType)

    /**
     * Add provided comic books
     * @param mode adding mode
     * @param paths comic book paths
     * @param flags adding flags
     */
    fun addComicBooks(mode: AddComicBookMode, paths: List<Uri>, flags: Int)
}

class ComicsListPresenterImpl(
    view: ComicsListView,
    dispatchers: Dispatchers,
    private val settings: ComicsSettings,
    private val viewModel: ComicsListViewModel
) : BaseStatefulPresenter<ComicsListView>(view, dispatchers), ComicsListPresenter {
    private var queryParams: QueryParams
        get() = viewModel.queryParams
        set(newQueryParams) {
            if (queryParams.filters != newQueryParams.filters) {
                showFilters(newQueryParams)
            }

            viewModel.queryParams = newQueryParams
        }

    override val currentSearchQuery: String?
        get() = queryParams.titleQuery

    init {
        viewModel.listState
            .observe(view) { comicsLoadingState ->
                when (comicsLoadingState) {
                    ComicsListState.Loading, ComicsListState.Idle -> {
                        view.showScreenState(ViewScreenState.STATE_LOADING)
                    }
                    is ComicsListState.Loaded -> {
                        val (list, totalCount) = comicsLoadingState

                        view.setComicsPagedList(list)

                        view.showScreenState(
                            when {
                                totalCount == 0L -> ViewScreenState.STATE_EMPTY
                                list.isEmpty() && totalCount > 0 -> ViewScreenState.STATE_NOTHING_FOUND
                                else -> ViewScreenState.STATE_DEFAULT
                            }
                        )
                    }
                }
            }

        viewModel.eventsFlow
            .observe(view) {
                when (val content = it) {
                    is ComicsMarkedAsRemoved -> view.onComicsMarkedRemoved(content.ids)
                    is ComicsOpened -> view.onComicAdded(content.result)
                }
            }

        viewModel.libraryState
            .observe(view) {
                when (it) {
                    Library.State.IDLE -> ComicsListView.SyncState.IDLE
                    Library.State.SYNCING -> ComicsListView.SyncState.IN_PROGRESS
                    Library.State.CHANGING -> ComicsListView.SyncState.DISABLED
                }.also { state ->
                    Logger.debug("Set view sync state to: $state")

                    view.onSyncStateChanged(state)
                }
            }

        presenterScope.launch {
            view.whenStarted {
                showFilters(queryParams)
            }
        }
    }

    override fun onCreate(state: Bundle?) {
        view.setComicListType(settings.getComicListType())

        val comicPageInitKey = if (state != null) {
            //restore titleQuery if needed
            onSearchQuery(state.getString(STATE_SEARCH_QUERY))

            state.getInt(STATE_PAGE_LAST_KEY, 0)
        } else {
            0
        }

        if(viewModel.listState.value == ComicsListState.Idle) {
            viewModel.loadList(COMIC_PAGE_SIZE, comicPageInitKey)
        }
    }

    override fun saveState(): Bundle {
        val outState = Bundle()

        outState.putString(STATE_SEARCH_QUERY, currentSearchQuery)

        viewModel.currentPageLastKey()?.also {
            outState.putInt(STATE_PAGE_LAST_KEY, it)
        }

        return outState
    }

    override fun onEditFilterClick() {
        view.showFiltersEditor(queryParams.filters)
    }

    override fun onSyncClick() {
        viewModel.sync()
    }

    override fun deleteComicBook(ids: Set<Long>, permanent: Boolean) {
        if (permanent) {
            viewModel.permanentRemove(ids)
        } else {
            viewModel.setRemovedState(ids, true)
        }
    }

    override fun undoComicRemove(ids: Set<Long>) {
        viewModel.setRemovedState(ids, false)
    }

    override fun onSortListClick() {
        view.showComicSortTypes(queryParams.sort)
    }

    override fun onSortSelected(selectedSort: QuerySort) {
        if (queryParams.sort == selectedSort) {
            return
        }

        queryParams = queryParams.buildNew { sort = selectedSort }
    }

    override fun onFiltersAccepted(acceptedFilters: Map<FilterGroup.ID, Filter>) {
        queryParams = queryParams.buildNew {
            acceptedFilters.forEach { (id, filter) -> addFilter(id, filter) }
        }
    }

    override fun removeFilter(groupId: FilterGroup.ID) {
        if (!queryParams.filters.containsKey(groupId)) {
            return
        }

        queryParams = queryParams.buildNew { removeFilter(groupId) }
    }

    override fun renameComicBook(id: Long, title: String) {
        viewModel.rename(id, title)
    }

    override fun onSearchQuery(query: String?) {
        if (queryParams.titleQuery == query) {
            return
        }

        queryParams = queryParams.buildNew { titleQuery = query }
    }

    override fun toggleComicCompletedMark(id: Long) {
        viewModel.toggleCompletedMark(id)
    }

    override fun setComicsCompletedMark(ids: Set<Long>, completed: Boolean) {
        viewModel.setComicsCompletedMark(ids, completed)
    }

    override fun onListTypeChanged(listType: ComicListViewType) {
        settings.saveComicListType(listType)
    }

    override fun addComicBooks(mode: AddComicBookMode, paths: List<Uri>, flags: Int) {
        viewModel.add(paths, mode, flags)
    }

    private fun showFilters(queryParams: QueryParams) {
        view.showFilters(queryParams.filters.map { (groupId, filter) ->
            FilterLabel(groupId, filter.title)
        })
    }

    private companion object {
        private const val COMIC_PAGE_SIZE = 15

        private const val STATE_SEARCH_QUERY = "search_query"
        private const val STATE_PAGE_LAST_KEY = "page_last_key"
    }
}