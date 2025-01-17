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

package app.seeneva.reader.logic

import androidx.paging.DataSource
import androidx.paging.PositionalDataSource
import app.seeneva.reader.common.coroutines.Dispatched
import app.seeneva.reader.common.coroutines.Dispatchers
import app.seeneva.reader.logic.entity.ComicListItem
import app.seeneva.reader.logic.entity.query.QueryParams
import app.seeneva.reader.logic.usecase.ComicListUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import kotlin.properties.Delegates

abstract class ComicsPagingDataSourceFactory : DataSource.Factory<Int, ComicListItem>() {
    private var currentDataSource: DataSource<Int, ComicListItem>? = null

    /**
     * Current comic book query params
     * Change it to invalidate last created [DataSource]
     */
    var queryParams by Delegates.observable<QueryParams?>(null) { _, old, new ->
        if (old != new) {
            currentDataSource?.invalidate()
        }
    }

    override fun create(): DataSource<Int, ComicListItem> =
        createInner().also { currentDataSource = it }

    protected abstract fun createInner(): DataSource<Int, ComicListItem>
}

/**
 * @param parentJob used to cancel source's coroutines if parent job cancelled
 */
internal class ComicsPagingDataSource(
    override val dispatchers: Dispatchers,
    private val useCase: ComicListUseCase,
    private val queryParams: QueryParams?,
    parentJob: Job?
) : PositionalDataSource<ComicListItem>(), CoroutineScope, Dispatched {
    override val coroutineContext = Job(parentJob) + dispatchers.main

    private var updatesJob: Job? = null

    init {
        coroutineContext[Job]!!.invokeOnCompletion { invalidate() }
    }

    override fun invalidate() {
        super.invalidate()

        //cancel coroutine job if this data source marked as invalid
        coroutineContext.cancel()
    }

    override fun loadInitial(
        params: LoadInitialParams,
        callback: LoadInitialCallback<ComicListItem>
    ) {
        //it seems that I can't do async work here. So I need run it on non Main Thread
        runBlocking {
            val totalCount = if (queryParams != null) {
                useCase.count(queryParams).toInt()
            } else {
                0
            }

            if (totalCount == 0) {
                callback.onResult(emptyList(), 0, 0)
            } else {
                val position = computeInitialLoadPosition(params, totalCount)
                val loadSize = computeInitialLoadSize(params, position, totalCount)

                val result = loadRangeInternal(position, loadSize)

                if (result.size == loadSize) {
                    callback.onResult(result, position, totalCount)
                } else {
                    invalidate()
                }
            }

            if (!isInvalid) {
                updatesJob?.cancel()

                //Subscribe to data update if paging data source is still valid
                updatesJob = useCase.subscribeUpdates(requireNotNull(queryParams))
                    .takeWhile { !isInvalid }
                    .take(1)
                    .onEach { invalidate() }
                    .launchIn(this@ComicsPagingDataSource)
            }
        }
    }

    override fun loadRange(params: LoadRangeParams, callback: LoadRangeCallback<ComicListItem>) {
        runBlocking {
            callback.onResult(loadRangeInternal(params.startPosition, params.loadSize))
        }
    }

    private suspend fun loadRangeInternal(position: Int, loadSize: Int): List<ComicListItem> {
        return useCase.getPage(position, loadSize, requireNotNull(queryParams))
    }

    /**
     * Comic book pagination source factory
     *
     * @param dispatchers
     * @param useCase
     * @param parentJob optional parent job. Used to cancel inner job
     */
    class Factory(
        private val dispatchers: Dispatchers,
        private val useCase: ComicListUseCase,
        private val parentJob: Job? = null
    ) : ComicsPagingDataSourceFactory() {
        override fun createInner(): DataSource<Int, ComicListItem> =
            ComicsPagingDataSource(dispatchers, useCase, queryParams, parentJob)
    }
}