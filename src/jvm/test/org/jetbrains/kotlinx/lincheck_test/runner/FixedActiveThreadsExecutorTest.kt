/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.runner

import org.jetbrains.kotlinx.lincheck.runner.*
import org.junit.*
import java.util.concurrent.*

class FixedActiveThreadsExecutorTest {
    @Test
    fun testSubmit() = FixedActiveThreadsExecutor("FixedActiveThreadsExecutorTest.testSubmit", 2).use { executor ->
        val executed = arrayOf(false, false)
        val tasks = Array<TestThreadExecution>(2) { iThread ->
            object : TestThreadExecution(iThread) {
                override fun run() {
                    executed[iThread] = true
                }
            }
        }
        executor.submitAndAwait(tasks, Long.MAX_VALUE / 2)
        check(executed.all { it })
    }

    @Test
    fun testResubmit() = FixedActiveThreadsExecutor("FixedActiveThreadsExecutorTest.testResubmit", 2).use { executor ->
        val executed = arrayOf(false, false)
        val tasks = Array<TestThreadExecution>(2) { iThread ->
            object : TestThreadExecution(iThread) {
                override fun run() {
                    executed[iThread] = true
                }
            }
        }
        executor.submitAndAwait(tasks, Long.MAX_VALUE / 2)
        executed.fill(false)
        executor.submitAndAwait(tasks, Long.MAX_VALUE / 2)
        check(executed.all { it })
    }

    @Test(timeout = 100_000)
    fun testSubmitTimeout() = FixedActiveThreadsExecutor("FixedActiveThreadsExecutorTest.testSubmitTimeout", 2).use { executor ->
        val tasks = Array<TestThreadExecution>(2) { iThread ->
            object : TestThreadExecution(iThread) {
                init {
                    this.iThread = iThread
                }

                override fun run() {
                    if (iThread == 1)
                        while (true);
                }
            }
        }
        try {
            executor.submitAndAwait(tasks, 200)
        } catch (e: TimeoutException) {
            return // TimeoutException is expected
        }
        check(false) { "TimeoutException was expected" }
    }
}
