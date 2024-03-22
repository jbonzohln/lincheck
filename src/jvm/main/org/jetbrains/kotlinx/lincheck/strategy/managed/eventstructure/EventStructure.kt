/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2022 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency.*
import org.jetbrains.kotlinx.lincheck.utils.*
import kotlin.reflect.KClass


class EventStructure(
    nParallelThreads: Int,
    val memoryInitializer: MemoryInitializer,
    private val loopDetector: LoopDetector,
    // TODO: refactor --- avoid using callbacks!
    private val reportInconsistencyCallback: ReportInconsistencyCallback,
    private val internalThreadSwitchCallback: InternalThreadSwitchCallback,
) {
    val mainThreadId = nParallelThreads
    private val initThreadId = nParallelThreads + 1
    private val maxThreadId = initThreadId
    private val nThreads = maxThreadId + 1

    /**
     * Mutable list of events of the event structure.
     */
    private val backtrackingPoints = sortedMutableListOf<BacktrackingPoint>()

    /**
     * Mutable list of the event structure events.
     */
    private val _events = sortedMutableListOf<AtomicThreadEvent>()

    /**
     * List of the event structure events.
     */
    val events: SortedList<AtomicThreadEvent> = _events

    /**
     * Root event of the whole event structure.
     * Its label is [InitializationLabel].
     */
    @SuppressWarnings("WeakerAccess")
    val root: AtomicThreadEvent

    /**
     * The root event of the currently being explored execution.
     * In other words, it is a choice point that has led to the current exploration.
     *
     * The label of this event should be of [LabelKind.Response] kind.
     */
    @SuppressWarnings("WeakerAccess")
    lateinit var currentExplorationRoot: Event
        private set

    /**
     * The mutable execution currently being explored.
     */
    private var _execution = MutableExtendedExecution(this.nThreads)

    /**
     * The execution currently being explored.
     */
    val execution: ExtendedExecution
        get() = _execution

    /**
     * The frontier representing an already replayed part of the execution currently being explored.
     */
    private var playedFrontier = MutableExecutionFrontier<AtomicThreadEvent>(this.nThreads)

    /**
     * An object managing the replay process of the execution currently being explored.
     */
    private var replayer = Replayer()

    /**
     * Synchronization algebra used for synchronization of events.
     */
    @SuppressWarnings("WeakerAccess")
    val syncAlgebra: SynchronizationAlgebra = AtomicSynchronizationAlgebra

    /**
     * The frontier encoding the subset of pinned events of the execution currently being explored.
     * Pinned events cannot be revisited and thus do not participate in the synchronization
     * with newly added events.
     */
    private var pinnedEvents = ExecutionFrontier<AtomicThreadEvent>(this.nThreads)

    /**
     * The object registry, storing information about all objects
     * created during the execution currently being explored.
     */
    private val objectRegistry = ObjectRegistry()


    /*
     * Map from blocked dangling events to their responses.
     * If event is blocked but the corresponding response has not yet arrived then it is mapped to null.
     */
    private val danglingEvents = mutableMapOf<AtomicThreadEvent, AtomicThreadEvent?>()

    private val delayedConsistencyCheckBuffer = mutableListOf<AtomicThreadEvent>()

    init {
        root = addRootEvent()
    }

    /* ************************************************************************* */
    /*      Exploration                                                          */
    /* ************************************************************************* */

    fun startNextExploration(): Boolean {
        loop@while (true) {
            val backtrackingPoint = rollbackTo { !it.visited }
                ?: return false
            backtrackingPoint.visit()
            resetExploration(backtrackingPoint)
            return true
        }
    }

    fun initializeExploration() {
        playedFrontier = MutableExecutionFrontier(nThreads)
        playedFrontier[initThreadId] = execution[initThreadId]!!.last()
        replayer.currentEvent.ensure {
            it != null && it.label is InitializationLabel
        }
        replayer.setNextEvent()
    }

    fun abortExploration() {
        // we abort the current exploration by resetting the current execution to its replayed part;
        // however, we need to handle blocking request in a special way --- we include their response part
        // to detect potential blocking response uniqueness violations
        // (e.g., two lock events unblocked by the same unlock event)
        for ((tid, event) in playedFrontier.threadMap.entries) {
            if (event == null)
                continue
            if (!(event.label.isRequest && event.label.isBlocking))
                continue
            val response = execution[tid, event.threadPosition + 1]
                ?: continue
            check(response.label.isResponse)
            // skip the response if it does not depend on any re-played event
            if (response.dependencies.any { it !in playedFrontier })
                continue
            playedFrontier.update(response)
        }
        _execution.reset(playedFrontier)
    }

    private fun rollbackTo(predicate: (BacktrackingPoint) -> Boolean): BacktrackingPoint? {
        val idx = backtrackingPoints.indexOfLast(predicate)
        val backtrackingPoint = backtrackingPoints.getOrNull(idx)
        val eventIdx = events.indexOfLast { it == backtrackingPoint?.event }
        backtrackingPoints.subList(idx + 1, backtrackingPoints.size).clear()
        _events.subList(eventIdx + 1, events.size).clear()
        return backtrackingPoint
    }

    private fun resetExploration(backtrackingPoint: BacktrackingPoint) {
        // get the event to backtrack to
        val event = backtrackingPoint.event.ensure {
            it.label is InitializationLabel || it.label.isResponse
        }
        // reset dangling events
        danglingEvents.clear()
        // set current exploration root
        currentExplorationRoot = event
        // reset current execution
        _execution.reset(backtrackingPoint.frontier)
        // copy pinned events and pin current re-exploration root event
        val pinnedEvents = backtrackingPoint.pinnedEvents.copy()
            .apply { set(event.threadId, event) }
        // add new event to current execution
        _execution.add(event)
        // do the same for blocked requests
        for (blockedRequest in backtrackingPoint.blockedRequests) {
            _execution.add(blockedRequest)
            // additionally, pin blocked requests if all their predecessors are also blocked ...
            if (blockedRequest.parent == pinnedEvents[blockedRequest.threadId]) {
                pinnedEvents[blockedRequest.threadId] = blockedRequest
            }
            // ... and mark it as dangling
            markBlockedDanglingRequest(blockedRequest)
        }
        // set pinned events
        this.pinnedEvents = pinnedEvents.ensure {
            execution.containsAll(it.events)
        }
        // check consistency of the whole execution
        _execution.checkConsistency()
        // set the replayer state
        val replayOrdering = _execution.executionOrderComputable.value.ordering
        replayer = Replayer(replayOrdering)
        // reset object indices --- retain only external events
        objectRegistry.retain { it.isExternal }
        // reset state of other auxiliary structures
        delayedConsistencyCheckBuffer.clear()
    }

    fun checkConsistency(): Inconsistency? {
        // TODO: set suddenInvocationResult?
        return _execution.checkConsistency()
    }

    /* ************************************************************************* */
    /*      Event creation                                                       */
    /* ************************************************************************* */

    /**
     * Class representing a backtracking point in the exploration of the program's executions.
     *
     * @property event The event at which to start a new exploration.
     * @property frontier The execution frontier at the point when the event was created.
     * @property pinnedEvents The frontier of pinned events that should not be
     *   considered for exploration branching.
     * @property blockedRequests The list of blocked request events.
     * @property visited Flag to indicate if this backtracking point has been visited.
     */
    private class BacktrackingPoint(
        val event: AtomicThreadEvent,
        val frontier: ExecutionFrontier<AtomicThreadEvent>,
        val pinnedEvents: ExecutionFrontier<AtomicThreadEvent>,
        val blockedRequests: List<AtomicThreadEvent>,
    ) : Comparable<BacktrackingPoint> {

        var visited: Boolean = false
            private set

        fun visit() {
            visited = true
        }

        override fun compareTo(other: BacktrackingPoint): Int {
            return event.id.compareTo(other.event.id)
        }
    }

    private fun createBacktrackingPoint(event: AtomicThreadEvent, conflicts: List<AtomicThreadEvent>) {
        val frontier = execution.toMutableFrontier().apply {
            cut(conflicts)
            // for already unblocked dangling requests,
            // also put their responses into the frontier
            addDanglingResponses(conflicts)
        }
        val danglingRequests = frontier.getDanglingRequests()
        val blockedRequests = danglingRequests
            // TODO: perhaps, we should change this to the list of requests to conflicting response events?
            .filter { it.label.isBlocking && it != event.parent && (it.label !is CoroutineSuspendLabel) }
        frontier.apply {
            cut(danglingRequests)
            set(event.threadId, event.parent)
        }
        val pinnedEvents = pinnedEvents.copy().apply {
            // TODO: can reorder cut and merge?
            val causalityFrontier = execution.calculateFrontier(event.causalityClock)
            merge(causalityFrontier)
            cut(conflicts)
            cut(getDanglingRequests())
            cut(event)
        }
        val backtrackingPoint = BacktrackingPoint(
            event = event,
            frontier = frontier,
            pinnedEvents = pinnedEvents,
            blockedRequests = blockedRequests,
        )
        backtrackingPoints.add(backtrackingPoint)
    }

    private fun createEvent(
        iThread: Int,
        label: EventLabel,
        parent: AtomicThreadEvent?,
        dependencies: List<AtomicThreadEvent>,
        visit: Boolean = true,
    ): AtomicThreadEvent? {
        val conflicts = getConflictingEvents(iThread, label, parent, dependencies)
        if (isCausalityViolated(parent, dependencies, conflicts))
            return null
        val allocation = objectRegistry[label.objectID]?.allocation
        val source = (label as? WriteAccessLabel)?.writeValue?.let {
            objectRegistry[it]?.allocation
        }
        val event = AtomicThreadEventImpl(
            label = label,
            parent = parent,
            senders = dependencies,
            allocation = allocation,
            source = source,
            dependencies = listOfNotNull(allocation, source) + dependencies,
        )
        _events.add(event)
        // if the event is not visited immediately,
        // then we create a breakpoint to visit it later
        if (!visit) {
            createBacktrackingPoint(event, conflicts)
        }
        return event
    }

    private fun getConflictingEvents(
        iThread: Int,
        label: EventLabel,
        parent: AtomicThreadEvent?,
        dependencies: List<AtomicThreadEvent>
    ): List<AtomicThreadEvent> {
        val position = parent?.let { it.threadPosition + 1 } ?: 0
        val conflicts = mutableListOf<AtomicThreadEvent>()
        // if the current execution already has an event in given position --- then it is conflict
        execution[iThread, position]?.also { conflicts.add(it) }
        // handle label specific cases
        // TODO: unify this logic for various kinds of labels?
        when {
            // lock-response synchronizing with our unlock is conflict
            label is LockLabel && label.isResponse && !label.isReentry -> run {
                val unlock = dependencies.first { it.label.asUnlockLabel(label.mutexID) != null }
                execution.forEach { event ->
                    if (event.label.satisfies<LockLabel> { isResponse && mutexID == label.mutexID }
                        && event.locksFrom == unlock) {
                        conflicts.add(event)
                    }
                }
            }
            // wait-response synchronizing with our notify is conflict
            label is WaitLabel && label.isResponse -> run {
                val notify = dependencies.first { it.label is NotifyLabel }
                if ((notify.label as NotifyLabel).isBroadcast)
                    return@run
                execution.forEach { event ->
                    if (event.label.satisfies<WaitLabel> { isResponse && mutexID == label.mutexID }
                        && event.notifiedBy == notify) {
                        conflicts.add(event)
                    }
                }
            }
            // TODO: add similar rule for read-exclusive-response?
        }
        return conflicts
    }

    private fun isCausalityViolated(
        parent: AtomicThreadEvent?,
        dependencies: List<AtomicThreadEvent>,
        conflicts: List<AtomicThreadEvent>,
    ): Boolean {
        var causalityViolation = false
        // Check that parent does not depend on conflicting events.
        if (parent != null) {
            causalityViolation = causalityViolation || conflicts.any { conflict ->
                causalityOrder.orEqual(conflict, parent)
            }
        }
        // Also check that dependencies do not causally depend on conflicting events.
        causalityViolation = causalityViolation || conflicts.any { conflict -> dependencies.any { dependency ->
            causalityOrder.orEqual(conflict, dependency)
        }}
        return causalityViolation
    }

    private fun MutableExecutionFrontier<AtomicThreadEvent>.addDanglingResponses(conflicts: List<AtomicThreadEvent>) {
        for ((request, response) in danglingEvents) {
            if (request in conflicts || response in conflicts)
                continue
            if (request == this[request.threadId] && response != null &&
                response.dependencies.all { it in this }) {
                this.update(response)
            }
        }
    }

    private fun addEventToCurrentExecution(event: AtomicThreadEvent) {
        // Check if the added event is replayed event.
        val isReplayedEvent = inReplayPhase(event.threadId)
        // Update current execution and replayed frontier.
        if (!isReplayedEvent) {
            _execution.add(event)
        }
        playedFrontier.update(event)
        // Unmark dangling request if its response was added.
        if (event.label.isResponse && event.label.isBlocking && event.parent in danglingEvents) {
            unmarkBlockedDanglingRequest(event.parent!!)
        }
        // If we are still in replay phase, but the added event is not a replayed event,
        // then save it to delayed events buffer to postpone its further processing.
        if (inReplayPhase()) {
            if (!isReplayedEvent) {
                delayedConsistencyCheckBuffer.add(event)
            }
            return
        }
        // If we are not in replay phase anymore, but the current event is replayed event,
        // it means that we just finished replay phase (i.e. the given event is the last replayed event).
        // In this case, we need to proceed with all postponed non-replayed events.
        if (isReplayedEvent) {
            for (delayedEvent in delayedConsistencyCheckBuffer) {
                if (delayedEvent.label.isSend) {
                    addSynchronizedEvents(delayedEvent)
                }
            }
            delayedConsistencyCheckBuffer.clear()
            return
        }
        // If we are not in the replay phase and the newly added event is not replayed, then proceed as usual.
        // Add synchronized events.
        if (event.label.isSend) {
            addSynchronizedEvents(event)
        }
        // Check consistency of the new event.
        val inconsistency = execution.inconsistency
        if (inconsistency != null) {
            reportInconsistencyCallback(inconsistency)
        }
    }

    /* ************************************************************************* */
    /*      Replaying                                                            */
    /* ************************************************************************* */

    private class Replayer(private val executionOrder: List<ThreadEvent>) {
        private var index: Int = 0
        private val size: Int = executionOrder.size

        constructor(): this(listOf())

        fun inProgress(): Boolean =
            (index < size)

        val currentEvent: AtomicThreadEvent?
            get() = if (inProgress()) (executionOrder[index] as? AtomicThreadEvent) else null

        fun setNextEvent() {
            index++
        }
    }

    fun inReplayPhase(): Boolean =
        replayer.inProgress()

    fun inReplayPhase(iThread: Int): Boolean {
        val frontEvent = playedFrontier[iThread]
            ?.ensure { it in _execution }
        return (frontEvent != execution.lastEvent(iThread))
    }

    // should only be called in replay phase!
    fun canReplayNextEvent(iThread: Int): Boolean {
        return iThread == replayer.currentEvent?.threadId
    }

    private fun tryReplayEvent(iThread: Int): AtomicThreadEvent? {
        if (inReplayPhase() && !canReplayNextEvent(iThread)) {
            // TODO: can we get rid of this?
            //   we can try to enforce more ordering invariants by grouping "atomic" events
            //   and also grouping events for which there is no reason to make switch in-between
            //   (e.g. `Alloc` followed by a `Write`).
            do {
                internalThreadSwitchCallback(iThread, SwitchReason.STRATEGY_SWITCH)
            } while (inReplayPhase() && !canReplayNextEvent(iThread))
        }
        return replayer.currentEvent
            ?.also { replayer.setNextEvent() }
    }

    /* ************************************************************************* */
    /*      Object tracking                                                      */
    /* ************************************************************************* */

    fun getValue(id: ValueID): OpaqueValue? = when (id) {
        NULL_OBJECT_ID -> null
        is PrimitiveID -> id.value.opaque()
        is ObjectID -> objectRegistry[id]?.obj
    }

    fun getValueID(value: OpaqueValue?): ValueID {
        if (value == null)
            return NULL_OBJECT_ID
        if (value.isPrimitive)
            return PrimitiveID(value.unwrap())
        return objectRegistry[value.unwrap()]?.id ?: INVALID_OBJECT_ID
    }

    fun computeValueID(value: OpaqueValue?): ValueID {
        if (value == null)
            return NULL_OBJECT_ID
        if (value.isPrimitive)
            return PrimitiveID(value.unwrap())
        objectRegistry[value.unwrap()]?.let {
            return it.id
        }
        val id = objectRegistry.nextObjectID
        val entry = ObjectEntry(id, value, root)
        val initLabel = (root.label as InitializationLabel)
        val className = value.unwrap().javaClass.simpleName
        objectRegistry.register(entry)
        initLabel.trackExternalObject(className, id)
        return entry.id
    }

    fun allocationEvent(id: ObjectID): AtomicThreadEvent? {
        return objectRegistry[id]?.allocation
    }

    /* ************************************************************************* */
    /*      Synchronization                                                      */
    /* ************************************************************************* */

    private val EventLabel.syncType
        get() = syncAlgebra.syncType(this)

    private fun EventLabel.synchronize(other: EventLabel) =
        syncAlgebra.synchronize(this, other)

    private fun synchronizationCandidates(label: EventLabel): Sequence<AtomicThreadEvent> {
        // TODO: generalize the checks for arbitrary synchronization algebra?
        return when {
            // write can synchronize with read-request events
            label is WriteAccessLabel ->
                execution.memoryAccessEventIndex.getReadRequests(label.location).asSequence()

            // read-request can synchronize only with write events
            label is ReadAccessLabel && label.isRequest ->
                execution.memoryAccessEventIndex.getWrites(label.location).asSequence()

            // read-response cannot synchronize with anything
            label is ReadAccessLabel && label.isResponse ->
                sequenceOf()

            // re-entry lock-request synchronizes only with initializing unlock
            (label is LockLabel && label.isReentry) ->
                sequenceOf(allocationEvent(label.mutexID)!!)

            // re-entry unlock does not participate in synchronization
            (label is UnlockLabel && label.isReentry) ->
                sequenceOf()

            // random labels do not synchronize
            label is RandomLabel -> sequenceOf()

            // otherwise we pessimistically assume that any event can potentially synchronize
            else -> execution.asSequence()
        }
    }

    private fun synchronizationCandidates(event: AtomicThreadEvent): Sequence<AtomicThreadEvent> {
        val label = event.label
        // consider all the candidates and apply additional filters
        val candidates = synchronizationCandidates(label)
            // take only the events from the current execution
            .filter { it in execution }
            // for a send event we additionally filter out ...
            .runIf(event.label.isSend) {
                filter {
                    // (1) all of its causal predecessors, because an attempt to synchronize with
                    //     these predecessors will result in a causality cycle
                    !causalityOrder(it, event) &&
                    // (2) pinned events, because their response part is pinned,
                    //     unless the pinned event is blocking dangling event
                    (!pinnedEvents.contains(it) || danglingEvents.contains(it))
                }
            }
        return when {
            /* For read-request events, we search for the last write to
             * the same memory location in the same thread.
             * We then filter out all causal predecessors of this last write,
             * because these events are "obsolete" ---
             * reading from them will result in coherence cycle and will violate consistency
             */
            label is ReadAccessLabel && label.isRequest -> {
                if (execution.memoryAccessEventIndex.isRaceFree(label.location)) {
                    val lastWrite = execution.memoryAccessEventIndex.getLastWrite(label.location)!!
                    return sequenceOf(lastWrite)
                }
                val threadReads = execution[event.threadId]!!.filter {
                    it.label.isResponse && (it.label as? ReadAccessLabel)?.location == label.location
                }
                val lastSeenWrite = threadReads.lastOrNull()?.readsFrom
                val staleWrites = threadReads
                    .map { it.readsFrom }
                    .filter { it != lastSeenWrite }
                    .distinct()
                val eventFrontier = execution.calculateFrontier(event.causalityClock)
                val racyWrites = calculateRacyWrites(label.location, eventFrontier)
                candidates.filter {
                    // !causalityOrder.lessThan(it, threadLastWrite) &&
                    !racyWrites.any { write -> causalityOrder(it, write) } &&
                    !staleWrites.any { write -> causalityOrder.orEqual(it, write) }
                }
            }

            label is WriteAccessLabel -> {
                if (execution.memoryAccessEventIndex.isReadWriteRaceFree(label.location)) {
                    return sequenceOf()
                }
                candidates
            }

            // an allocation event, at the point when it is added to the execution,
            // cannot synchronize with anything, because there are no events yet
            // that access the allocated object
            label is ObjectAllocationLabel -> {
                return sequenceOf()
            }

            label is CoroutineSuspendLabel && label.isRequest -> {
                // filter-out InitializationLabel to prevent creating cancellation response
                // TODO: refactor!!!
                candidates.filter { it.label !is InitializationLabel }
            }

            else -> candidates
        }
    }

    /**
     * Adds to the event structure a list of events obtained as a result of synchronizing given [event]
     * with the events contained in the current exploration. For example, if
     * `e1 @ A` is the given event labeled by `A` and `e2 @ B` is some event in the event structure labeled by `B`,
     * then the resulting list will contain event labeled by `C = A \+ B` if `C` is defined (i.e. not null),
     * and the list of dependencies of this new event will be equal to `listOf(e1, e2)`.
     *
     * @return list of added events
     */
    private fun addSynchronizedEvents(event: AtomicThreadEvent): List<AtomicThreadEvent> {
        val candidates = synchronizationCandidates(event)
        val syncEvents = when (event.label.syncType) {
            SynchronizationType.Binary -> addBinarySynchronizedEvents(event, candidates)
            SynchronizationType.Barrier -> addBarrierSynchronizedEvents(event, candidates)
            else -> return listOf()
        }
        // if there are responses to blocked dangling requests, then set the response of one of these requests
        for (syncEvent in syncEvents) {
            val requestEvent = syncEvent.parent
                ?.takeIf { it.label.isRequest && it.label.isBlocking }
                ?: continue
            if (requestEvent in danglingEvents && getUnblockingResponse(requestEvent) == null) {
                setUnblockingResponse(syncEvent)
                // mark corresponding backtracking point as visited;
                // search from the end, because the search event was added recently,
                // and thus should be located near the end of the list
                backtrackingPoints.last { it.event == syncEvent }.apply { visit() }
                break
            }
        }
        return syncEvents
    }

    private fun addBinarySynchronizedEvents(
        event: AtomicThreadEvent,
        candidates: Sequence<AtomicThreadEvent>
    ): List<AtomicThreadEvent> {
        require(event.label.syncType == SynchronizationType.Binary)
        // TODO: sort resulting events according to some strategy?
        return candidates
            .asIterable()
            .mapNotNull { other ->
                val syncLab = event.label.synchronize(other.label)
                    ?: return@mapNotNull null
                val (parent, dependency) = when {
                    event.label.isRequest -> event to other
                    other.label.isRequest -> other to event
                    else -> unreachable()
                }
                check(parent.label.isRequest && dependency.label.isSend && syncLab.isResponse)
                Triple(syncLab, parent, dependency)
            }.sortedBy { (_, _, dependency) ->
                dependency
            }.mapNotNull { (syncLab, parent, dependency) ->
                createEvent(
                    iThread = parent.threadId,
                    label = syncLab,
                    parent = parent,
                    dependencies = listOf(dependency),
                    visit = false,
                )
            }
    }

    private fun addBarrierSynchronizedEvents(
        event: AtomicThreadEvent,
        candidates: Sequence<AtomicThreadEvent>
    ): List<AtomicThreadEvent> {
        require(event.label.syncType == SynchronizationType.Barrier)
        val (syncLab, dependencies) =
            candidates.fold(event.label to listOf(event)) { (lab, deps), candidateEvent ->
                candidateEvent.label.synchronize(lab)?.let {
                    (it to deps + candidateEvent)
                } ?: (lab to deps)
            }
        if (syncLab.isBlocking && !syncLab.isUnblocked)
            return listOf()
        // We assume that at most, one of the events participating into synchronization
        // is a request event, and the result of synchronization is a response event.
        check(syncLab.isResponse)
        val parent = dependencies.first { it.label.isRequest }
        val responseEvent = createEvent(
            iThread = parent.threadId,
            label = syncLab,
            parent = parent,
            dependencies = dependencies.filter { it != parent },
            visit = false,
        )
        return listOfNotNull(responseEvent)
    }

    /* ************************************************************************* */
    /*      Generic event addition utilities (per event kind)                    */
    /* ************************************************************************* */

    private fun addRootEvent(): AtomicThreadEvent {
        // we do not mark root event as visited purposefully;
        // this is just a trick to make the first call to `startNextExploration`
        // to pick the root event as the next event to explore from.
        val label = InitializationLabel(initThreadId, mainThreadId) { location ->
            val value = memoryInitializer(location)
            computeValueID(value)
        }
        return createEvent(initThreadId, label, parent = null, dependencies = emptyList(), visit = false)!!
            .also { event ->
                val id = STATIC_OBJECT_ID
                val entry = ObjectEntry(id, STATIC_OBJECT.opaque(), event)
                objectRegistry.register(entry)
                addEventToCurrentExecution(event)
            }
    }

    private fun addEvent(iThread: Int, label: EventLabel, dependencies: List<AtomicThreadEvent>): AtomicThreadEvent {
        tryReplayEvent(iThread)?.let { event ->
            check(event.label == label)
            addEventToCurrentExecution(event)
            return event
        }
        val parent = playedFrontier[iThread]
        return createEvent(iThread, label, parent, dependencies)!!.also { event ->
            addEventToCurrentExecution(event)
        }
    }

    private fun addSendEvent(iThread: Int, label: EventLabel): AtomicThreadEvent {
        require(label.isSend)
        return addEvent(iThread, label, listOf())
    }

    private fun addRequestEvent(iThread: Int, label: EventLabel): AtomicThreadEvent {
        require(label.isRequest)
        return addEvent(iThread, label, listOf())
    }

    private fun addResponseEvents(requestEvent: AtomicThreadEvent): Pair<AtomicThreadEvent?, List<AtomicThreadEvent>> {
        require(requestEvent.label.isRequest)
        tryReplayEvent(requestEvent.threadId)?.let { event ->
            check(event.label.isResponse)
            check(event.parent == requestEvent)
            // TODO: refactor & move to other replay-related functions
            val readyToReplay = event.dependencies.all {
                dependency -> dependency in playedFrontier
            }
            if (!readyToReplay) {
                return (null to listOf())
            }
            check(event.label == event.resynchronize(syncAlgebra))
            addEventToCurrentExecution(event)
            return event to listOf(event)
        }
        if (requestEvent.label.isBlocking && requestEvent in danglingEvents) {
            val event = getUnblockingResponse(requestEvent)
                ?: return (null to listOf())
            check(event.label.isResponse)
            check(event.parent == requestEvent)
            addEventToCurrentExecution(event)
            return event to listOf(event)
        }
        val responseEvents = addSynchronizedEvents(requestEvent)
        if (responseEvents.isEmpty()) {
            markBlockedDanglingRequest(requestEvent)
            return (null to listOf())
        }
        // TODO: use some other strategy to select the next event in the current exploration?
        // TODO: check consistency of chosen event!
        val chosenEvent = responseEvents.last().also { event ->
            check(event == backtrackingPoints.last().event)
            backtrackingPoints.last().visit()
            addEventToCurrentExecution(event)
        }
        return (chosenEvent to responseEvents)
    }

    /* ************************************************************************* */
    /*      Blocking events handling                                             */
    /* ************************************************************************* */

    fun isBlockedRequest(request: AtomicThreadEvent): Boolean {
        require(request.label.isRequest && request.label.isBlocking)
        return (request == playedFrontier[request.threadId])
    }

    fun isBlockedDanglingRequest(request: AtomicThreadEvent): Boolean {
        require(request.label.isRequest && request.label.isBlocking)
        return execution.isBlockedDanglingRequest(request)
    }

    fun isBlockedAwaitingRequest(request: AtomicThreadEvent): Boolean {
        require(isBlockedRequest(request))
        if (inReplayPhase(request.threadId)) {
            return !canReplayNextEvent(request.threadId)
        }
        if (request in danglingEvents) {
            return danglingEvents[request] == null
        }
        return false
    }

    fun getBlockedRequest(iThread: Int): AtomicThreadEvent? =
        playedFrontier[iThread]?.takeIf { it.label.isRequest && it.label.isBlocking }

    fun getBlockedAwaitingRequest(iThread: Int): AtomicThreadEvent? =
        getBlockedRequest(iThread)?.takeIf { isBlockedAwaitingRequest(it) }

    private fun markBlockedDanglingRequest(request: AtomicThreadEvent) {
        require(isBlockedDanglingRequest(request))
        check(request !in danglingEvents)
        check(danglingEvents.keys.all { it.threadId != request.threadId })
        danglingEvents.put(request, null).ensureNull()
    }

    private fun unmarkBlockedDanglingRequest(request: AtomicThreadEvent) {
        require(request.label.isRequest && request.label.isBlocking)
        require(!isBlockedDanglingRequest(request))
        require(request in danglingEvents)
        danglingEvents.remove(request)
    }

    private fun setUnblockingResponse(response: AtomicThreadEvent) {
        require(response.label.isResponse && response.label.isBlocking)
        val request = response.parent
            .ensure { it != null }
            .ensure { isBlockedDanglingRequest(it!!) }
            .ensure { it in danglingEvents }
        danglingEvents.put(request!!, response).ensureNull()
    }

    private fun getUnblockingResponse(request: AtomicThreadEvent): AtomicThreadEvent? {
        require(isBlockedDanglingRequest(request))
        require(request in danglingEvents)
        return danglingEvents[request]
    }

    /* ************************************************************************* */
    /*      Specific event addition utilities (per event class)                  */
    /* ************************************************************************* */

    fun addThreadStartEvent(iThread: Int): AtomicThreadEvent {
        val label = ThreadStartLabel(
            threadId = iThread,
            kind = LabelKind.Request,
        )
        val requestEvent = addRequestEvent(iThread, label)
        val (responseEvent, responseEvents) = addResponseEvents(requestEvent)
        checkNotNull(responseEvent)
        check(responseEvents.size == 1)
        return responseEvent
    }

    fun addThreadFinishEvent(iThread: Int): AtomicThreadEvent {
        val label = ThreadFinishLabel(
            threadId = iThread,
        )
        return addSendEvent(iThread, label)
    }

    fun addThreadForkEvent(iThread: Int, forkThreadIds: Set<Int>): AtomicThreadEvent {
        val label = ThreadForkLabel(
            forkThreadIds = forkThreadIds
        )
        return addSendEvent(iThread, label)
    }

    fun addThreadJoinEvent(iThread: Int, joinThreadIds: Set<Int>): AtomicThreadEvent {
        val label = ThreadJoinLabel(
            kind = LabelKind.Request,
            joinThreadIds = joinThreadIds,
        )
        val requestEvent = addRequestEvent(iThread, label)
        val (responseEvent, responseEvents) = addResponseEvents(requestEvent)
        // TODO: handle case when ThreadJoin is not ready yet
        checkNotNull(responseEvent)
        check(responseEvents.size == 1)
        return responseEvent
    }

    fun addObjectAllocationEvent(iThread: Int, value: OpaqueValue): AtomicThreadEvent {
        tryReplayEvent(iThread)?.let { event ->
            val id = event.label.objectID
            val entry = ObjectEntry(id, value, event)
            objectRegistry.register(entry)
            addEventToCurrentExecution(event)
            return event
        }
        val id = objectRegistry.nextObjectID
        val label = ObjectAllocationLabel(
            objectID = id,
            className = value.unwrap().javaClass.simpleName,
            memoryInitializer = { location ->
                val initValue = memoryInitializer(location)
                computeValueID(initValue)
            },
        )
        val parent = playedFrontier[iThread]
        val dependencies = listOf<AtomicThreadEvent>()
        return createEvent(iThread, label, parent, dependencies)!!.also { event ->
            val entry = ObjectEntry(id, value, event)
            objectRegistry.register(entry)
            addEventToCurrentExecution(event)
        }
    }

    fun addWriteEvent(iThread: Int, codeLocation: Int, location: MemoryLocation, kClass: KClass<*>, value: OpaqueValue?,
                      isExclusive: Boolean = false): AtomicThreadEvent {
        val label = WriteAccessLabel(
            location = location,
            writeValue = computeValueID(value),
            isExclusive = isExclusive,
            kClass = kClass,
            codeLocation = codeLocation,
        )
        return addSendEvent(iThread, label)
    }

    fun addReadEvent(iThread: Int, codeLocation: Int, location: MemoryLocation, kClass: KClass<*>,
                     isExclusive: Boolean = false): AtomicThreadEvent {
        // we first create a read-request event with unknown (null) value,
        // value will be filled later in the read-response event
        val label = ReadAccessLabel(
            kind = LabelKind.Request,
            location = location,
            readValue = NULL_OBJECT_ID,
            isExclusive = isExclusive,
            kClass = kClass,
            codeLocation = codeLocation,
        )
        val requestEvent = addRequestEvent(iThread, label)
        val (responseEvent, _) = addResponseEvents(requestEvent)
        // TODO: think again --- is it possible that there is no write to read-from?
        //  Probably not, because in Kotlin variables are always initialized by default?
        //  What about initialization-related issues?
        checkNotNull(responseEvent)
        if (isSpinLoopBoundReached(responseEvent)) {
            internalThreadSwitchCallback(iThread, SwitchReason.SPIN_BOUND)
        }
        return responseEvent
    }

    fun addLockRequestEvent(iThread: Int, mutex: OpaqueValue,
                            isReentry: Boolean = false, reentrancyDepth: Int = 1,
                            isSynthetic: Boolean = false): AtomicThreadEvent {
        val label = LockLabel(
            kind = LabelKind.Request,
            mutexID = computeValueID(mutex) as ObjectID,
            isReentry = isReentry,
            reentrancyDepth = reentrancyDepth,
            isSynthetic = isSynthetic,
        )
        return addRequestEvent(iThread, label)
    }

    fun addLockResponseEvent(lockRequest: AtomicThreadEvent): AtomicThreadEvent? {
        require(lockRequest.label.isRequest && lockRequest.label is LockLabel)
        return addResponseEvents(lockRequest).first
    }

    fun addUnlockEvent(iThread: Int, mutex: OpaqueValue,
                       isReentry: Boolean = false, reentrancyDepth: Int = 1,
                       isSynthetic: Boolean = false): AtomicThreadEvent {
        val label = UnlockLabel(
            mutexID = computeValueID(mutex) as ObjectID,
            isReentry = isReentry,
            reentrancyDepth = reentrancyDepth,
            isSynthetic = isSynthetic,
        )
        return addSendEvent(iThread, label)
    }

    fun addWaitRequestEvent(iThread: Int, mutex: OpaqueValue): AtomicThreadEvent {
        val label = WaitLabel(
            kind = LabelKind.Request,
            mutexID = computeValueID(mutex) as ObjectID,
        )
        return addRequestEvent(iThread, label)

    }

    fun addWaitResponseEvent(waitRequest: AtomicThreadEvent): AtomicThreadEvent? {
        require(waitRequest.label.isRequest && waitRequest.label is WaitLabel)
        return addResponseEvents(waitRequest).first
    }

    fun addNotifyEvent(iThread: Int, mutex: OpaqueValue, isBroadcast: Boolean): AtomicThreadEvent {
        // TODO: we currently ignore isBroadcast flag and handle `notify` similarly as `notifyAll`.
        //   It is correct wrt. Java's semantics, since `wait` can wake-up spuriously according to the spec.
        //   Thus multiple wake-ups due to single notify can be interpreted as spurious.
        //   However, if one day we will want to support wait semantics without spurious wake-ups
        //   we will need to revisit this.
        val label = NotifyLabel(
            mutexID = computeValueID(mutex) as ObjectID,
            isBroadcast = isBroadcast
        )
        return addSendEvent(iThread, label)
    }

    fun addParkRequestEvent(iThread: Int): AtomicThreadEvent {
        val label = ParkLabel(LabelKind.Request, iThread)
        return addRequestEvent(iThread, label)
    }

    fun addParkResponseEvent(parkRequest: AtomicThreadEvent): AtomicThreadEvent? {
        require(parkRequest.label.isRequest && parkRequest.label is ParkLabel)
        return addResponseEvents(parkRequest).first
    }

    fun addUnparkEvent(iThread: Int, unparkingThreadId: Int): AtomicThreadEvent {
        val label = UnparkLabel(unparkingThreadId)
        return addSendEvent(iThread, label)
    }

    fun addCoroutineSuspendRequestEvent(iThread: Int, iActor: Int, promptCancellation: Boolean = false): AtomicThreadEvent {
        val label = CoroutineSuspendLabel(LabelKind.Request, iThread, iActor, promptCancellation = promptCancellation)
        return addRequestEvent(iThread, label)
    }

    fun addCoroutineSuspendResponseEvent(iThread: Int, iActor: Int): AtomicThreadEvent {
        val request = getBlockedRequest(iThread).ensure { event ->
            (event != null) && event.label.satisfies<CoroutineSuspendLabel> { actorId == iActor }
        }!!
        val (response, events) = addResponseEvents(request)
        check(events.size == 1)
        return response!!
    }

    fun addCoroutineCancelResponseEvent(iThread: Int, iActor: Int): AtomicThreadEvent {
        val request = getBlockedRequest(iThread).ensure { event ->
            (event != null) && event.label.satisfies<CoroutineSuspendLabel> { actorId == iActor }
        }!!
        val label = (request.label as CoroutineSuspendLabel).getResponse(root.label)!!
        tryReplayEvent(iThread)?.let { event ->
            check(event.label == label)
            addEventToCurrentExecution(event)
            return event
        }
        return createEvent(iThread, label, parent = request, dependencies = listOf(root))!!.also { event ->
            addEventToCurrentExecution(event)
        }
    }

    fun addCoroutineResumeEvent(iThread: Int, iResumedThread: Int, iResumedActor: Int): AtomicThreadEvent {
        val label = CoroutineResumeLabel(iResumedThread, iResumedActor)
        return addSendEvent(iThread, label)
    }

    fun addActorStartEvent(iThread: Int, actor: Actor): AtomicThreadEvent {
        val label = ActorLabel(SpanLabelKind.Start, iThread, actor)
        return addEvent(iThread, label, dependencies = listOf())
    }

    fun addActorEndEvent(iThread: Int, actor: Actor): AtomicThreadEvent {
        val label = ActorLabel(SpanLabelKind.End, iThread, actor)
        return addEvent(iThread, label, dependencies = listOf())
    }

    fun tryReplayRandomEvent(iThread: Int): AtomicThreadEvent? {
        tryReplayEvent(iThread)?.let { event ->
            check(event.label is RandomLabel)
            addEventToCurrentExecution(event)
            return event
        }
        return null
    }

    fun addRandomEvent(iThread: Int, generated: Int): AtomicThreadEvent {
        val label = RandomLabel(generated)
        val parent = playedFrontier[iThread]
        return createEvent(iThread, label, parent, dependencies = emptyList())!!.also { event ->
            addEventToCurrentExecution(event)
        }
    }

    /* ************************************************************************* */
    /*      Miscellaneous                                                        */
    /* ************************************************************************* */

    /**
     * Calculates the view for specific memory location observed at the given point of execution
     * given by [observation] vector clock. Memory location view is a vector clock itself
     * that maps each thread id to the last write access event to the given memory location at the given thread.
     *
     * @param location the memory location.
     * @param observation the vector clock specifying the point of execution for the view calculation.
     * @return the view (i.e. vector clock) for the given memory location.
     *
     * TODO: move to Execution?
     */
    fun calculateMemoryLocationView(
        location: MemoryLocation,
        observation: ExecutionFrontier<AtomicThreadEvent>
    ): ExecutionFrontier<AtomicThreadEvent> =
        observation.threadMap.map { (tid, event) ->
            val lastWrite = event
                ?.ensure { it in execution }
                ?.pred(inclusive = true) {
                    it.label.asMemoryAccessLabel(location)?.takeIf { label -> label.isWrite } != null
                }
            (tid to lastWrite as? AtomicThreadEvent?)
        }.let {
            executionFrontierOf(*it.toTypedArray())
        }

    /**
     * Calculates a list of all racy writes to specific memory location observed at the given point of execution
     * given by [observation] vector clock. In other words, the resulting list contains all program-order maximal
     * racy writes observed at the given point.
     *
     * @param location the memory location.
     * @param observation the vector clock specifying the point of execution for the view calculation.
     * @return list of program-order maximal racy write events.
     *
     * TODO: move to Execution?
     */
    fun calculateRacyWrites(
        location: MemoryLocation,
        observation: ExecutionFrontier<AtomicThreadEvent>
    ): List<ThreadEvent> {
        val writes = calculateMemoryLocationView(location, observation).events
        return writes.filter { write ->
            !writes.any { other ->
                causalityOrder(write, other)
            }
        }
    }

    private fun isSpinLoopBoundReached(event: ThreadEvent): Boolean {
        check(event.label is ReadAccessLabel && event.label.isResponse)
        val readLabel = (event.label as ReadAccessLabel)
        val location = readLabel.location
        val readValue = readLabel.readValue
        val codeLocation = readLabel.codeLocation
        // a potential spin-loop occurs when we have visited the same code location more than N times
        if (loopDetector.codeLocationCounter(event.threadId, codeLocation) < SPIN_BOUND)
            return false
        // if the last 3 reads with the same code location read the same value,
        // then we consider this a spin-loop
        var spinEvent: ThreadEvent = event
        var spinCounter = SPIN_BOUND
        while (spinCounter-- > 0) {
            spinEvent = spinEvent.pred {
                it.label.isResponse && it.label.satisfies<ReadAccessLabel> {
                    this.location == location && this.codeLocation == codeLocation
                }
            } ?: return false
            if ((spinEvent.label as ReadAccessLabel).readValue != readValue)
                return false
        }
        return true
    }

}