package org.jetbrains.kotlinx.lincheck.distributed

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

interface Node {
    fun onMessage(message : Message)
    fun onTimer(timer : String) {}
    fun afterFailure() {}
}

abstract class BlockingReceiveNodeImp : Node {
    private val messageQueue = LinkedBlockingQueue<Message>()
    override fun onMessage(message: Message) {
        messageQueue.add(message)
    }

    fun receive(timeout : Long, timeUnit: TimeUnit = TimeUnit.MILLISECONDS) : Message? {
        return messageQueue.poll(timeout, timeUnit)
    }

    fun receive() : Message? {
        return messageQueue.poll()
    }
}