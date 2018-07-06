/*
 * Copyright 2016-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.experimental.android

import android.os.*
import android.view.*
import kotlinx.coroutines.experimental.*
import java.util.concurrent.*
import kotlin.coroutines.experimental.*

/**
 * Dispatches execution onto Android main UI thread and provides native [delay][Delay.delay] support.
 */
val UI = HandlerContext(Handler(Looper.getMainLooper()), "UI")

/**
 * Represents an arbitrary [Handler] as a implementation of [CoroutineDispatcher].
 */
fun Handler.asCoroutineDispatcher() = HandlerContext(this)

private const val MAX_DELAY = Long.MAX_VALUE / 2 // cannot delay for too long on Android

/**
 * Implements [CoroutineDispatcher] on top of an arbitrary Android [Handler].
 * @param handler a handler.
 * @param name an optional name for debugging.
 */
public class HandlerContext(
    private val handler: Handler,
    private val name: String? = null
) : CoroutineDispatcher(), Delay {

    /**
     * Dispatcher which executes given block immediately if [isDispatchNeeded] is already in the right handler context
     * (current looper is the same as [handler] looper). If not, block is dispatched regularly via [handler]
     */
    public val immediate: HandlerContext  by lazy { HandlerContext(handler, name, true) }

    private var invokeImmediately: Boolean = false

    private constructor(handler: Handler, name: String?, invokeImmediately: Boolean) : this(handler, name) {
        this.invokeImmediately = invokeImmediately
    }

    @Volatile
    private var _choreographer: Choreographer? = null

    override fun isDispatchNeeded(context: CoroutineContext): Boolean {
        return !invokeImmediately || Looper.myLooper() != handler.looper
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        handler.post(block)
    }

    override fun scheduleResumeAfterDelay(time: Long, unit: TimeUnit, continuation: CancellableContinuation<Unit>) {
        handler.postDelayed({
            with(continuation) { resumeUndispatched(Unit) }
        }, unit.toMillis(time).coerceAtMost(MAX_DELAY))
    }

    override fun invokeOnTimeout(time: Long, unit: TimeUnit, block: Runnable): DisposableHandle {
        handler.postDelayed(block, unit.toMillis(time).coerceAtMost(MAX_DELAY))
        return object : DisposableHandle {
            override fun dispose() {
                handler.removeCallbacks(block)
            }
        }
    }

    /**
     * Awaits the next animation frame and returns frame time in nanoseconds.
     */
    public suspend fun awaitFrame(): Long {
        // fast path when choreographer is already known
        val choreographer = _choreographer
        if (choreographer != null) {
            return suspendCancellableCoroutine { cont ->
                postFrameCallback(choreographer, cont)
            }
        }
        // post into looper thread thread to figure it out
        return suspendCancellableCoroutine { cont ->
           handler.post {
                updateChoreographerAndPostFrameCallback(cont)
           }
        }
    }

    private fun updateChoreographerAndPostFrameCallback(cont: CancellableContinuation<Long>) {
        val choreographer = _choreographer ?:
            Choreographer.getInstance()!!.also { _choreographer = it }
        postFrameCallback(choreographer, cont)
    }

    private fun postFrameCallback(choreographer: Choreographer, cont: CancellableContinuation<Long>) {
        choreographer.postFrameCallback { nanos ->
            with(cont) { resumeUndispatched(nanos) }
        }
    }

    override fun toString() = name ?: handler.toString()
    override fun equals(other: Any?): Boolean = other is HandlerContext && other.handler === handler
    override fun hashCode(): Int = System.identityHashCode(handler)
}