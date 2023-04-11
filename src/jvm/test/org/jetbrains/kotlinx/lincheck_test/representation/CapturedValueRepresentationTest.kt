/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.representation

import org.jetbrains.kotlinx.lincheck.LincheckMode
import org.jetbrains.kotlinx.lincheck.LincheckOptions
import org.jetbrains.kotlinx.lincheck.LincheckOptionsImpl
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.jetbrains.kotlinx.lincheck_test.util.checkLincheckOutput
import org.junit.Test

/**
 * This test checks that values captured in an incorrect interleaving have proper representation.
 * `toString` method is used only for primitive types and their wrappers.
 * For other classes we use simplified representation to avoid problems with concurrent modification or
 * not completely initialized objects (e.g, with `ConcurrentModificationException`)
 */
class CapturedValueRepresentationTest : VerifierState() {
    private var counter = 0
    private var outerClass1 = OuterDataClass(0)
    private var outerClass2 = OuterDataClass(0)
    private var innerClass = InnerClass()
    private var otherInnerClass = InnerClass()
    private var primitiveArray = IntArray(1)
    private var objectArray = Array(1) { "" }

    @Operation
    fun operation(): Int {
        outerClass1
        outerClass2
        innerClass
        innerClass
        otherInnerClass
        primitiveArray
        objectArray
        return counter++
    }

    @Test
    fun test() = LincheckOptions {
        this as LincheckOptionsImpl
        maxThreads = 2
        maxOperationsInThread = 1
        generateBeforeAndAfterParts = false
        mode = LincheckMode.ModelChecking
    }
        .checkImpl(this::class.java)
        .checkLincheckOutput("captured_value.txt")

    override fun extractState(): Any = counter

    private class InnerClass
}

private data class OuterDataClass(val a: Int)
