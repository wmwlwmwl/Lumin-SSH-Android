package com.lumin.ssh.android

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SerializedShellIoTest {
    @Test
    fun concurrentOperationsNeverOverlap() {
        val io = SerializedShellIo()
        val active = AtomicInteger(0)
        val overlapped = AtomicBoolean(false)
        val completed = AtomicInteger(0)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)

        repeat(4) {
            pool.submit {
                start.await()
                io.withLock {
                    if (active.incrementAndGet() != 1) overlapped.set(true)
                    Thread.sleep(20)
                    active.decrementAndGet()
                    completed.incrementAndGet()
                }
            }
        }

        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
        assertEquals(4, completed.get())
        assertFalse(overlapped.get())
    }

    @Test
    fun exceptionPropagatesAndLockRemainsUsable() {
        val io = SerializedShellIo()

        val error = assertFailsWith<IOException> {
            io.withLock { throw IOException("写入失败") }
        }
        assertEquals("写入失败", error.message)
        assertEquals(42, io.withLock { 42 })
    }

    @Test
    fun closeWaitsForInFlightWrite() {
        val io = SerializedShellIo()
        val enteredWrite = CountDownLatch(1)
        val finishWrite = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        pool.submit {
            io.withLock {
                enteredWrite.countDown()
                finishWrite.await()
            }
        }
        assertTrue(enteredWrite.await(1, TimeUnit.SECONDS))

        pool.submit {
            io.withLock { closeFinished.countDown() }
        }
        assertFalse(closeFinished.await(50, TimeUnit.MILLISECONDS))
        finishWrite.countDown()
        assertTrue(closeFinished.await(1, TimeUnit.SECONDS))

        pool.shutdownNow()
    }
}
