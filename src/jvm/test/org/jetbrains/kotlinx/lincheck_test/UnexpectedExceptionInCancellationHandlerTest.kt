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

import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.strategy.*

class UnexpectedExceptionInCancellationHandlerTest: AbstractLincheckTest(UnexpectedExceptionFailure::class) {
    @Operation(cancellableOnSuspension = true)
    suspend fun foo() {
        suspendCancellableCoroutine<Unit> { cont ->
            cont.invokeOnCancellation {
                throw InternalLincheckTestUnexpectedException
            }
        }
    }


    override fun LincheckOptionsImpl.customize() {
        maxThreads = 1
        maxOperationsInThread = 1
        generateBeforeAndAfterParts = false
        sequentialImplementation = UnexpectedExceptionInCancellationHandlerTestSequential::class.java
    }
}

class UnexpectedExceptionInCancellationHandlerTestSequential() {
    suspend fun foo() {
        suspendCancellableCoroutine<Unit> {}
    }
}
