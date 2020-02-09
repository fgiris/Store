/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dropbox.store.rx.test

import com.dropbox.android.external.store4.ResponseOrigin.Cache
import com.dropbox.android.external.store4.ResponseOrigin.Fetcher
import com.dropbox.android.external.store4.ResponseOrigin.Persister
import com.dropbox.android.external.store4.Store
import com.dropbox.android.external.store4.StoreRequest
import com.dropbox.android.external.store4.StoreResponse.Data
import com.dropbox.android.external.store4.StoreResponse.Loading
import com.dropbox.store.rx.InMemoryRxPersister
import com.dropbox.store.rx.observe
import com.dropbox.store.rx.rxSingleStore
import com.dropbox.store.rx.withSinglePersister
import com.google.common.truth.FailureMetadata
import com.google.common.truth.Subject
import com.google.common.truth.Truth
import com.google.common.truth.Truth.assertThat
import io.reactivex.Single
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@FlowPreview
@ExperimentalCoroutinesApi
@RunWith(JUnit4::class)
class RxStoreTest {
    private val testScope = TestCoroutineScope()

    @Test
    fun getAndFresh() {
        val fetcher = FakeFetcher(
            3 to "three-1",
            3 to "three-2"
        )
        val pipeline: Store<Int, String> = rxSingleStore<Int, String>({ fetcher.fetch(it) })
            .scope(testScope)
            .build()

        pipeline.observe(StoreRequest.cached(3, refresh = false))
            .test()
            .awaitCount(2)
            .assertValuesOnly(
                Loading(
                    origin = Fetcher
                ), Data(
                    value = "three-1",
                    origin = Fetcher
                )
            )

        pipeline.observe(StoreRequest.cached(3, refresh = false))
            .test()
            .awaitCount(1)
            .assertValuesOnly(
                Data(
                    value = "three-1",
                    origin = Cache
                )
            )

        pipeline.observe(StoreRequest.fresh(3))
            .test()
            .awaitCount(1)
            .assertValuesOnly(
                Loading(
                    origin = Fetcher
                ),
                Data(
                    value = "three-2",
                    origin = Fetcher
                )
            )

        pipeline.observe(StoreRequest.cached(3, refresh = false))
            .test()
            .awaitCount(1)
            .assertValuesOnly(
                Data(
                    value = "three-2",
                    origin = Cache
                )
            )
    }

    @Test
    fun getAndFresh_withPersister()= testScope.runBlockingTest {
        val fetcher = FakeFetcher(
            3 to "three-1",
            3 to "three-2"
        )
        val persister = InMemoryRxPersister<Int, String>()

        val pipeline = rxSingleStore<Int, String> { fetcher.fetch(it) }
            .withSinglePersister(
                reader = persister::read,
                writer = persister::write
            )
            .scope(testScope)
            .build()


        pipeline.observe(StoreRequest.cached(3, refresh = false))
            .test()
            .awaitCount(2)
            .assertValuesOnly(
                Loading(
                    origin = Fetcher
                ),
                Data(
                    value = "three-1",
                    origin = Fetcher
                )
            )

        pipeline.observe(StoreRequest.cached(3, refresh = false))
            .test()
            .awaitCount(2)
            .assertValuesOnly(
                Data(
                    value = "three-1",
                    origin = Cache
                ),
                // note that we still get the data from persister as well as we don't listen to
                // the persister for the cached items unless there is an active stream, which
                // means cache can go out of sync w/ the persister
                Data(
                    value = "three-1",
                    origin = Persister
                )
            )
        pipeline.observe(StoreRequest.fresh(3))
            .test()
            .awaitCount(4)
            .assertValuesOnly(
                Loading(
                    origin = Fetcher
                ),
                Data(
                    value = "three-2",
                    origin = Fetcher
                )
            )

        // pipeline.observe(StoreRequest.cached(3, refresh = false))
        //     .test()
        //     .awaitCount(2)
        //     .assertValuesOnly(
        //         Data(
        //             value = "three-2",
        //             origin = Cache
        //         ),
        //         Data(
        //             value = "three-2",
        //             origin = Persister
        //         )
        //     )
    }
    //
    // @Test
    // fun streamAndFresh_withPersister() = testScope.runBlockingTest {
    //     val fetcher = FakeFetcher(
    //         3 to "three-1",
    //         3 to "three-2"
    //     )
    //     val persister = InMemoryPersister<Int, String>()
    //
    //     val pipeline = build(
    //         nonFlowingFetcher = { fetcher.fetch(it).await() },
    //         persisterReader = persister::read,
    //         persisterWriter = persister::write,
    //         enableCache = true
    //     )
    //
    //     assertThat(pipeline.stream(StoreRequest.cached(3, refresh = true)))
    //         .emitsExactly(
    //             Loading(
    //                 origin = Fetcher
    //             ),
    //             Data(
    //                 value = "three-1",
    //                 origin = Fetcher
    //             )
    //         )
    //
    //     assertThat(pipeline.stream(StoreRequest.cached(3, refresh = true)))
    //         .emitsExactly(
    //             Data(
    //                 value = "three-1",
    //                 origin = Cache
    //             ),
    //             Data(
    //                 value = "three-1",
    //                 origin = Persister
    //             ),
    //             Loading(
    //                 origin = Fetcher
    //             ),
    //             Data(
    //                 value = "three-2",
    //                 origin = Fetcher
    //             )
    //         )
    // }
    //
    // @Test
    // fun streamAndFresh() = testScope.runBlockingTest {
    //     val fetcher = FakeFetcher(
    //         3 to "three-1",
    //         3 to "three-2"
    //     )
    //     val pipeline = build<Int, String, String>(
    //         nonFlowingFetcher = { fetcher.fetch(it).await() },
    //         enableCache = true
    //     )
    //
    //     assertThat(pipeline.stream(StoreRequest.cached(3, refresh = true)))
    //         .emitsExactly(
    //             Loading(
    //                 origin = Fetcher
    //             ),
    //             Data(
    //                 value = "three-1",
    //                 origin = Fetcher
    //             )
    //         )
    //
    //     assertThat(pipeline.stream(StoreRequest.cached(3, refresh = true)))
    //         .emitsExactly(
    //             Data(
    //                 value = "three-1",
    //                 origin = Cache
    //             ),
    //             Loading(
    //                 origin = Fetcher
    //             ),
    //             Data(
    //                 value = "three-2",
    //                 origin = Fetcher
    //             )
    //         )
    // }
    //
    // @Test
    // fun skipCache() = testScope.runBlockingTest {
    //     val fetcher = FakeFetcher(
    //         3 to "three-1",
    //         3 to "three-2"
    //     )
    //     val pipeline = build<Int, String, String>(
    //         nonFlowingFetcher = { fetcher.fetch(it).await() },
    //         enableCache = true
    //     )
    //
    //     assertThat(pipeline.stream(StoreRequest.skipMemory(3, refresh = false)))
    //         .emitsExactly(
    //             Loading(
    //                 origin = Fetcher
    //             ),
    //             Data(
    //                 value = "three-1",
    //                 origin = Fetcher
    //             )
    //         )
    //
    //     assertThat(pipeline.stream(StoreRequest.skipMemory(3, refresh = false)))
    //         .emitsExactly(
    //             Loading(
    //                 origin = Fetcher
    //             ),
    //             Data(
    //                 value = "three-2",
    //                 origin = Fetcher
    //             )
    //         )
    // }
    //
    // @Test
    // fun flowingFetcher() = testScope.runBlockingTest {
    //     val fetcher = FlowingFakeFetcher(
    //         3 to "three-1",
    //         3 to "three-2"
    //     )
    //     val persister = InMemoryPersister<Int, String>()
    //
    //     val pipeline = build(
    //         flowingFetcher = fetcher::createFlow,
    //         persisterReader = persister::read,
    //         persisterWriter = persister::write,
    //         enableCache = false
    //     )
    //
    //     assertThat(pipeline.stream(StoreRequest.fresh(3)))
    //         .emitsExactly(
    //             Loading(
    //                 origin = Fetcher
    //             ),
    //             Data(
    //                 value = "three-1",
    //                 origin = Fetcher
    //             ),
    //             Data(
    //                 value = "three-2",
    //                 origin = Fetcher
    //             )
    //         )
    //     assertThat(pipeline.stream(StoreRequest.cached(3, refresh = true)))
    //         .emitsExactly(
    //             Data(
    //                 value = "three-2",
    //                 origin = Persister
    //             ),
    //             Loading(
    //                 origin = Fetcher
    //             ),
    //             Data(
    //                 value = "three-1",
    //                 origin = Fetcher
    //             ),
    //             Data(
    //                 value = "three-2",
    //                 origin = Fetcher
    //             )
    //         )
    // }

    // @Test
    // fun diskChangeWhileNetworkIsFlowing_simple() = testScope.runBlockingTest {
    //     val persister = InMemoryPersister<Int, String>().asFlowable()
    //     val pipeline = build(
    //         flowingFetcher = {
    //             flow {
    //             }
    //         },
    //         flowingPersisterReader = persister::flowReader,
    //         persisterWriter = persister::flowWriter,
    //         enableCache = false
    //     )
    //
    //     launch {
    //         delay(10)
    //         persister.flowWriter(3, "local-1")
    //     }
    //     assertThat(pipeline.stream(StoreRequest.cached(3, refresh = true)))
    //         .emitsExactly(
    //             Loading(
    //                 origin = Fetcher
    //             ),
    //             Data(
    //                 value = "local-1",
    //                 origin = Persister
    //             )
    //         )
    // }
    //
    // @Test
    // fun diskChangeWhileNetworkIsFlowing_overwrite() = testScope.runBlockingTest {
    //     val persister = InMemoryPersister<Int, String>().asFlowable()
    //     val pipeline = build(
    //         flowingFetcher = {
    //             flow {
    //                 delay(10)
    //                 emit("three-1")
    //                 delay(10)
    //                 emit("three-2")
    //             }
    //         },
    //         flowingPersisterReader = persister::flowReader,
    //         persisterWriter = persister::flowWriter,
    //         enableCache = false
    //     )
    //     launch {
    //         delay(5)
    //         persister.flowWriter(3, "local-1")
    //         delay(10) // go in between two server requests
    //         persister.flowWriter(3, "local-2")
    //     }
    //     assertThat(pipeline.stream(StoreRequest.cached(3, refresh = true)))
    //         .emitsExactly(
    //             Loading(
    //                 origin = Fetcher
    //             ),
    //             Data(
    //                 value = "local-1",
    //                 origin = Persister
    //             ),
    //             Data(
    //                 value = "three-1",
    //                 origin = Fetcher
    //             ),
    //             Data(
    //                 value = "local-2",
    //                 origin = Persister
    //             ),
    //             Data(
    //                 value = "three-2",
    //                 origin = Fetcher
    //             )
    //         )
    // }
    //
    // @Test
    // fun errorTest() = testScope.runBlockingTest {
    //     val exception = IllegalArgumentException("wow")
    //     val persister = InMemoryPersister<Int, String>().asFlowable()
    //     val pipeline = build(
    //         nonFlowingFetcher = {
    //             throw exception
    //         },
    //         flowingPersisterReader = persister::flowReader,
    //         persisterWriter = persister::flowWriter,
    //         enableCache = false
    //     )
    //     launch {
    //         delay(10)
    //         persister.flowWriter(3, "local-1")
    //     }
    //     assertThat(pipeline.stream(StoreRequest.cached(key = 3, refresh = true)))
    //         .emitsExactly(
    //             Loading(
    //                 origin = Fetcher
    //             ),
    //             StoreResponse.Error(
    //                 error = exception,
    //                 origin = Fetcher
    //             ),
    //             Data(
    //                 value = "local-1",
    //                 origin = Persister
    //             )
    //         )
    //     assertThat(pipeline.stream(StoreRequest.cached(key = 3, refresh = true)))
    //         .emitsExactly(
    //             Data(
    //                 value = "local-1",
    //                 origin = Persister
    //             ),
    //             Loading(
    //                 origin = Fetcher
    //             ),
    //             StoreResponse.Error(
    //                 error = exception,
    //                 origin = Fetcher
    //             )
    //         )
    // }
    //
    // @Test
    // fun `GIVEN no sourceOfTruth and cache hit WHEN stream cached data without refresh THEN no fetch is triggered AND receives following network updates`() =
    //     testScope.runBlockingTest {
    //         val fetcher = FakeFetcher(
    //             3 to "three-1",
    //             3 to "three-2"
    //         )
    //         val store = build<Int, String, String>(
    //             nonFlowingFetcher = { fetcher.fetch(it).await() },
    //             enableCache = true
    //         )
    //         val firstFetch = store.fresh(3)
    //         assertThat(firstFetch).isEqualTo("three-1")
    //         val secondCollect = mutableListOf<StoreResponse<String>>()
    //         val collection = launch {
    //             store.stream(StoreRequest.cached(3, refresh = false)).collect {
    //                 secondCollect.add(it)
    //             }
    //         }
    //         testScope.runCurrent()
    //         assertThat(secondCollect).containsExactly(
    //             Data(
    //                 value = "three-1",
    //                 origin = Cache
    //             )
    //         )
    //         // trigger another fetch from network
    //         val secondFetch = store.fresh(3)
    //         assertThat(secondFetch).isEqualTo("three-2")
    //         testScope.runCurrent()
    //         // make sure cached also received it
    //         assertThat(secondCollect).containsExactly(
    //             Data(
    //                 value = "three-1",
    //                 origin = Cache
    //             ),
    //             Data(
    //                 value = "three-2",
    //                 origin = Fetcher
    //             )
    //         )
    //         collection.cancelAndJoin()
    //     }
    //
    // @Test
    // fun `GIVEN sourceOfTruth and cache hit WHEN stream cached data without refresh THEN no fetch is triggered AND receives following network updates`() =
    //     testScope.runBlockingTest {
    //         val fetcher = FakeFetcher(
    //             3 to "three-1",
    //             3 to "three-2"
    //         )
    //         val persister = InMemoryPersister<Int, String>()
    //         val pipeline = build(
    //             nonFlowingFetcher = { fetcher.fetch(it).await() },
    //             persisterReader = persister::read,
    //             persisterWriter = persister::write,
    //             enableCache = true
    //         )
    //         val firstFetch = pipeline.fresh(3)
    //         assertThat(firstFetch).isEqualTo("three-1")
    //         val secondCollect = mutableListOf<StoreResponse<String>>()
    //         val collection = launch {
    //             pipeline.stream(StoreRequest.cached(3, refresh = false)).collect {
    //                 secondCollect.add(it)
    //             }
    //         }
    //         testScope.runCurrent()
    //         assertThat(secondCollect).containsExactly(
    //             Data(
    //                 value = "three-1",
    //                 origin = Cache
    //             ),
    //             Data(
    //                 value = "three-1",
    //                 origin = Persister
    //             )
    //         )
    //         // trigger another fetch from network
    //         val secondFetch = pipeline.fresh(3)
    //         assertThat(secondFetch).isEqualTo("three-2")
    //         testScope.runCurrent()
    //         // make sure cached also received it
    //         assertThat(secondCollect).containsExactly(
    //             Data(
    //                 value = "three-1",
    //                 origin = Cache
    //             ),
    //             Data(
    //                 value = "three-1",
    //                 origin = Persister
    //             ),
    //             Data(
    //                 value = "three-2",
    //                 origin = Fetcher
    //             )
    //         )
    //         collection.cancelAndJoin()
    //     }
    //
    // @Test
    // fun `GIVEN cache and no sourceOfTruth WHEN 3 cached streams with refresh AND 1st has slow collection THEN 1st streams gets 3 fetch updates AND other streams get cache result AND fetch result`() =
    //     testScope.runBlockingTest {
    //         val fetcher = FakeFetcher(
    //             3 to "three-1",
    //             3 to "three-2",
    //             3 to "three-3"
    //         )
    //         val pipeline = build<Int, String, String>(
    //             nonFlowingFetcher = { fetcher.fetch(it).await() },
    //             enableCache = true
    //         )
    //         val fetcher1Collected = mutableListOf<StoreResponse<String>>()
    //         val fetcher1Job = async {
    //             pipeline.stream(StoreRequest.cached(3, refresh = true)).collect {
    //                 fetcher1Collected.add(it)
    //                 delay(1_000)
    //             }
    //         }
    //         testScope.advanceUntilIdle()
    //         assertThat(fetcher1Collected).isEqualTo(
    //             listOf(
    //                 Loading<String>(origin = Fetcher),
    //                 Data(origin = Fetcher, value = "three-1")
    //             )
    //         )
    //         assertThat(pipeline.stream(StoreRequest.cached(3, refresh = true)))
    //             .emitsExactly(
    //                 Data(origin = Cache, value = "three-1"),
    //                 Loading(origin = Fetcher),
    //                 Data(origin = Fetcher, value = "three-2")
    //             )
    //         assertThat(pipeline.stream(StoreRequest.cached(3, refresh = true)))
    //             .emitsExactly(
    //                 Data(origin = Cache, value = "three-2"),
    //                 Loading(origin = Fetcher),
    //                 Data(origin = Fetcher, value = "three-3")
    //             )
    //         testScope.advanceUntilIdle()
    //         assertThat(fetcher1Collected).isEqualTo(
    //             listOf(
    //                 Loading<String>(origin = Fetcher),
    //                 Data(origin = Fetcher, value = "three-1"),
    //                 Data(origin = Fetcher, value = "three-2"),
    //                 Data(origin = Fetcher, value = "three-3")
    //             )
    //         )
    //         fetcher1Job.cancelAndJoin()
    //     }
    //
    // @Test
    // fun `GIVEN cache and no sourceOfTruth WHEN 2 cached streams with refresh THEN first streams gets 2 fetch updates AND 2nd stream gets cache result and fetch result`() =
    //     testScope.runBlockingTest {
    //         val fetcher = FakeFetcher(
    //             3 to "three-1",
    //             3 to "three-2"
    //         )
    //         val pipeline = build<Int, String, String>(
    //             nonFlowingFetcher = { fetcher.fetch(it).await() },
    //             enableCache = true
    //         )
    //         val fetcher1Collected = mutableListOf<StoreResponse<String>>()
    //         val fetcher1Job = async {
    //             pipeline.stream(StoreRequest.cached(3, refresh = true)).collect {
    //                 fetcher1Collected.add(it)
    //             }
    //         }
    //         testScope.runCurrent()
    //         assertThat(fetcher1Collected).isEqualTo(
    //             listOf(
    //                 Loading<String>(origin = Fetcher),
    //                 Data(origin = Fetcher, value = "three-1")
    //             )
    //         )
    //         assertThat(pipeline.stream(StoreRequest.cached(3, refresh = true)))
    //             .emitsExactly(
    //                 Data(origin = Cache, value = "three-1"),
    //                 Loading(origin = Fetcher),
    //                 Data(origin = Fetcher, value = "three-2")
    //             )
    //         testScope.runCurrent()
    //         assertThat(fetcher1Collected).isEqualTo(
    //             listOf(
    //                 Loading<String>(origin = Fetcher),
    //                 Data(origin = Fetcher, value = "three-1"),
    //                 Data(origin = Fetcher, value = "three-2")
    //             )
    //         )
    //         fetcher1Job.cancelAndJoin()
    //     }
    //
    // suspend fun Store<Int, String>.get(request: StoreRequest<Int>) =
    //     this.stream(request).filter { it.dataOrNull() != null }.first()
    //
    // suspend fun Store<Int, String>.get(key: Int) = get(
    //     StoreRequest.cached(
    //         key = key,
    //         refresh = false
    //     )
    // )
    //
    // private class FlowingFakeFetcher<Key, Output>(
    //     vararg val responses: Pair<Key, Output>
    // ) {
    //     fun createFlow(key: Key) = flow {
    //         responses.filter {
    //             it.first == key
    //         }.forEach {
    //             // we delay here to avoid collapsing fetcher values, otherwise, there is a
    //             // possibility that consumer won't be fast enough to get both values before new
    //             // value overrides the previous one.
    //             delay(1)
    //             emit(it.second)
    //         }
    //     }
    // }
    //
    // private fun <Key : Any, Input : Any, Output : Any> build(
    //     nonFlowingFetcher: (suspend (Key) -> Input)? = null,
    //     flowingFetcher: ((Key) -> Flow<Input>)? = null,
    //     persisterReader: (suspend (Key) -> Output?)? = null,
    //     flowingPersisterReader: ((Key) -> Flow<Output?>)? = null,
    //     persisterWriter: (suspend (Key, Input) -> Unit)? = null,
    //     persisterDelete: (suspend (Key) -> Unit)? = null,
    //     enableCache: Boolean
    // ): Store<Key, Output> {
    //     check(nonFlowingFetcher != null || flowingFetcher != null) {
    //         "need to provide a fetcher"
    //     }
    //     check(nonFlowingFetcher == null || flowingFetcher == null) {
    //         "need 1 fetcher"
    //     }
    //     check(persisterReader == null || flowingPersisterReader == null) {
    //         "need 0 or 1 persister"
    //     }
    //
    //     return if (nonFlowingFetcher != null) {
    //         StoreBuilder.fromNonFlow(
    //             nonFlowingFetcher
    //         )
    //     } else {
    //         StoreBuilder.from(
    //             flowingFetcher!!
    //         )
    //     }.scope(testScope)
    //         .let {
    //             if (enableCache) {
    //                 it
    //             } else {
    //                 it.disableCache()
    //             }
    //         }.let {
    //             @Suppress("UNCHECKED_CAST")
    //             when {
    //                 flowingPersisterReader != null -> it.persister(
    //                     reader = flowingPersisterReader,
    //                     writer = persisterWriter!!,
    //                     delete = persisterDelete
    //                 )
    //                 persisterReader != null -> it.nonFlowingPersister(
    //                     reader = persisterReader,
    //                     writer = persisterWriter!!,
    //                     delete = persisterDelete
    //                 )
    //                 else -> it
    //             } as StoreBuilder<Key, Output>
    //         }.build()
    // }
}

@UseExperimental(ExperimentalCoroutinesApi::class)
internal fun <T> TestCoroutineScope.assertThat(flow: Flow<T>): FlowSubject<T> {
    return Truth.assertAbout(FlowSubject.Factory<T>(this)).that(flow)
}

@UseExperimental(ExperimentalCoroutinesApi::class)
internal class FlowSubject<T> constructor(
    failureMetadata: FailureMetadata,
    private val testCoroutineScope: TestCoroutineScope,
    private val actual: Flow<T>
) : Subject(failureMetadata, actual) {
    /**
     * Takes all items in the flow that are available by collecting on it as long as there are
     * active jobs in the given [TestCoroutineScope].
     *
     * It ensures all expected items are dispatched as well as no additional unexpected items are
     * dispatched.
     */
    suspend fun emitsExactly(vararg expected: T) {
        val collectedSoFar = mutableListOf<T>()
        val collectionCoroutine = testCoroutineScope.async {
            actual.collect {
                collectedSoFar.add(it)
                if (collectedSoFar.size > expected.size) {
                    Truth.assertWithMessage("Too many emissions in the flow (only first additional item is shown)")
                        .that(collectedSoFar)
                        .isEqualTo(expected)
                }
            }
        }
        testCoroutineScope.advanceUntilIdle()
        if (!collectionCoroutine.isActive) {
            collectionCoroutine.getCompletionExceptionOrNull()?.let {
                throw it
            }
        }
        collectionCoroutine.cancelAndJoin()
        Truth.assertWithMessage("Flow didn't exactly emit expected items")
            .that(collectedSoFar)
            .isEqualTo(expected.toList())
    }

    class Factory<T>(
        private val testCoroutineScope: TestCoroutineScope
    ) : Subject.Factory<FlowSubject<T>, Flow<T>> {
        override fun createSubject(metadata: FailureMetadata, actual: Flow<T>): FlowSubject<T> {
            return FlowSubject(
                failureMetadata = metadata,
                actual = actual,
                testCoroutineScope = testCoroutineScope
            )
        }
    }
}

class FakeFetcher<Key, Output>(
    vararg val responses: Pair<Key, Output>
) {
    private var index = 0
    @Suppress("RedundantSuspendModifier") // needed for function reference
    fun fetch(key: Key): Single<Output> = Single.fromCallable {
        if (index >= responses.size) {
            throw AssertionError("unexpected fetch request")
        }
        val pair = responses[index++]
        assertThat(pair.first).isEqualTo(key)
        pair.second
    }
}

@ExperimentalCoroutinesApi
suspend fun <Key, Output> InMemoryPersister<Key, Output>.asFlowable() = SimplePersisterAsFlowable(
    reader = this::read,
    writer = this::write
)

class InMemoryPersister<Key, Output> {
    private val data = mutableMapOf<Key, Output>()

    @Suppress("RedundantSuspendModifier") // for function reference
    suspend fun read(key: Key) = data[key]

    @Suppress("RedundantSuspendModifier") // for function reference
    suspend fun write(key: Key, output: Output) {
        data[key] = output
    }

    @Suppress("RedundantSuspendModifier") // for function reference
    suspend fun deleteByKey(key: Key) {
        data.remove(key)
    }

    @Suppress("RedundantSuspendModifier") // for function reference
    suspend fun deleteAll() {
        data.clear()
    }

    fun peekEntry(key: Key): Output? {
        return data[key]
    }
}

@ExperimentalCoroutinesApi
class SimplePersisterAsFlowable<Key, Input, Output>(
    private val reader: suspend (Key) -> Output?,
    private val writer: suspend (Key, Input) -> Unit,
    private val delete: (suspend (Key) -> Unit)? = null
) {
    private val versionTracker = KeyTracker<Key>()

    fun flowReader(key: Key): Flow<Output?> = flow {
        versionTracker.keyFlow(key).collect {
            emit(reader(key))
        }
    }

    suspend fun flowWriter(key: Key, input: Input) {
        writer(key, input)
        versionTracker.invalidate(key)
    }

    suspend fun flowDelete(key: Key) {
        delete?.let {
            it(key)
            versionTracker.invalidate(key)
        }
    }
}

/**
 * helper class which provides Flows for Keys that can be tracked.
 */
@ExperimentalCoroutinesApi
internal class KeyTracker<Key> {
    private val lock = Mutex()
    // list of open key channels
    private val channels = mutableMapOf<Key, KeyChannel>()

    // for testing
    internal fun activeKeyCount() = channels.size

    /**
     * invalidates the given key. If there are flows returned from [keyFlow] for the given [key],
     * they'll receive a new emission
     */
    suspend fun invalidate(key: Key) {
        lock.withLock {
            channels[key]
        }?.channel?.send(Unit)
    }

    /**
     * Returns a Flow that emits once and then every time the given [key] is invalidated via
     * [invalidate]
     */
    suspend fun keyFlow(key: Key): Flow<Unit> {
        // it is important to allocate KeyChannel lazily (ony when the returned flow is collected
        // from). Otherwise, we might just create many of them that are never observed hence never
        // cleaned up
        return flow {
            val keyChannel = lock.withLock {
                channels.getOrPut(key) {
                    KeyChannel(
                        channel = BroadcastChannel<Unit>(Channel.CONFLATED).apply {
                            // start w/ an initial value.
                            offer(Unit)
                        }
                    )
                }.also {
                    it.acquire() // refcount
                }
            }
            try {
                emitAll(keyChannel.channel.openSubscription())
            } finally {
                lock.withLock {
                    keyChannel.release()
                    if (keyChannel.channel.isClosedForSend) {
                        channels.remove(key)
                    }
                }
            }
        }
    }

    /**
     * A data structure to count how many active flows we have on this channel
     */
    private data class KeyChannel(
        val channel: BroadcastChannel<Unit>,
        var collectors: Int = 0
    ) {
        fun acquire() {
            collectors++
        }

        fun release() {
            collectors--
            if (collectors == 0) {
                channel.close()
            }
        }
    }
}