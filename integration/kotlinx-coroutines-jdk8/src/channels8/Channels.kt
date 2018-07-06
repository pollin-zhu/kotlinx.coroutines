/*
 * Copyright 2016-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.experimental.channels8

import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.consumeEach
import kotlinx.coroutines.experimental.channels.produce
import kotlinx.coroutines.experimental.runBlocking
import java.util.*
import java.util.function.BiConsumer
import java.util.function.Consumer
import java.util.stream.Collector
import java.util.stream.Stream
import java.util.stream.StreamSupport
import kotlin.coroutines.experimental.CoroutineContext

/**
 * Creates a [ProducerJob] to read all element of the [Stream].
 */
public fun <E> Stream<E>.asReceiveChannel(context: CoroutineContext = CommonPool): ReceiveChannel<E> = produce(context) {
    for (element in this@asReceiveChannel)
        send(element)
}

/**
 * Creates a [Stream] of elements in this [ReceiveChannel].
 */
public fun <E : Any> ReceiveChannel<E>.asStream(): Stream<E> = StreamSupport.stream<E>(SpliteratorAdapter(this), false)

/**
 * Applies the [collector] to the [ReceiveChannel]
 */
public suspend fun <T, A : Any, R> ReceiveChannel<T>.collect(collector: Collector<T, A, R>): R {
    val container: A = collector.supplier().get()
    val accumulator: BiConsumer<A, T> = collector.accumulator()
    consumeEach { accumulator.accept(container, it) }
    return collector.finisher().apply(container)
}

private class SpliteratorAdapter<E : Any>(val channel: ReceiveChannel<E>) : Spliterator<E> {
    override fun estimateSize(): Long = Long.MAX_VALUE

    override fun forEachRemaining(action: Consumer<in E>) {
        runBlocking {
            for (element in channel)
                action.accept(element)
        }
    }

    override fun tryAdvance(action: Consumer<in E>): Boolean = runBlocking {
        val element = channel.receiveOrNull()
        if (element != null) {
            action.accept(element)
            true
        } else false
    }

    override fun characteristics(): Int = characteristics

    override fun trySplit(): Spliterator<E>? = null

    private companion object {
        @JvmStatic
        private val characteristics = Spliterator.ORDERED or Spliterator.NONNULL
    }
}