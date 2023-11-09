/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.jetbrains.kotlinx.lincheck_test.util.*
import org.junit.*
import sun.nio.ch.lincheck.*
import kotlin.reflect.*

abstract class AbstractLincheckTest(
    private vararg val expectedFailures: KClass<out LincheckFailure>
) : VerifierState() {
    open fun <O : Options<O, *>> O.customize() {}
    override fun extractState(): Any = System.identityHashCode(this)

    private fun <O : Options<O, *>> O.runInternalTest() {
        val failure: LincheckFailure? = checkImpl(this@AbstractLincheckTest::class.java)
        if (failure === null) {
            assert(expectedFailures.isEmpty()) {
                "This test should fail, but no error has been occurred (see the logs for details)"
            }
        } else {
            failure.trace?.let { checkTraceHasNoLincheckEvents(it.toString()) }
            assert(expectedFailures.contains(failure::class)) {
                "This test has failed with an unexpected error: \n $failure"
            }
        }
        // TODO: when a deadlock happens, threads remain alive. We need to fix this issue
        // TODO: and add the check below that requires all Lincheck threads to be finished
        // TODO: after the test completes.
        // waitUntilAllLincheckThreadsAreFinished()
    }

    @Test(timeout = TIMEOUT)
    fun testWithStressStrategy(): Unit = StressOptions().run {
        invocationsPerIteration(5_000)
        commonConfiguration()
        runInternalTest()
    }

    @Test(timeout = TIMEOUT)
    fun testWithModelCheckingStrategy(): Unit = ModelCheckingOptions().run {
        invocationsPerIteration(1_000)
        commonConfiguration()
        runInternalTest()
    }

    private fun <O : Options<O, *>> O.commonConfiguration(): Unit = run {
        iterations(30)
        actorsBefore(2)
        threads(3)
        actorsPerThread(2)
        actorsAfter(2)
        minimizeFailedScenario(false)
        customize()
    }
}

private const val TIMEOUT = 100_000L

private fun waitUntilAllLincheckThreadsAreFinished() {
    val deadline = System.currentTimeMillis() + 1000
    while (true) {
        if (Thread.getAllStackTraces().keys.filterIsInstance<TestThread>().isEmpty())
            return
        if (System.currentTimeMillis() > deadline) {
            val threadDump = Thread.getAllStackTraces().filterKeys { it is TestThread }.map {
                val threadNumber = (it.key as TestThread).threadNumber
                val stackTrace = it.value.joinToString("\n")
                "Thread-$threadNumber\n$stackTrace\n"
            }.joinToString("\n")
            error("Lincheck threads has not been finished, see the thread dump:\n$threadDump\n")
        }
        Thread.yield()
    }
}