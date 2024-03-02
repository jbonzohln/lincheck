/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.strategy.managed

import java.lang.reflect.*
import java.lang.reflect.Array as ReflectArray
import java.util.concurrent.atomic.*


typealias ValueMapper = (ValueID) -> OpaqueValue?

interface MemoryLocation {
    val objID: ObjectID

    fun read(valueMapper: ValueMapper): Any?
    fun write(valueMapper: ValueMapper, value: Any?)
}

class StaticFieldMemoryLocation(
    strategy: ManagedStrategy,
    val className: String,
    val fieldName: String,
) : MemoryLocation {

    override val objID: ObjectID = STATIC_OBJECT_ID

    private val field: Field by lazy {
        resolveClass(strategy, className = className)
            .getDeclaredField(fieldName)
            .apply { isAccessible = true }
    }

    override fun read(valueMapper: ValueMapper): Any? {
        return field.get(null)
    }

    override fun write(valueMapper: ValueMapper, value: Any?) {
        field.set(null, value)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other)
            return true
        return (other is StaticFieldMemoryLocation)
                && (className == other.className)
                && (fieldName == other.fieldName)
    }

    override fun hashCode(): Int {
        var result = className.hashCode()
        result = 31 * result + fieldName.hashCode()
        return result
    }

    override fun toString(): String =
        "$className::$fieldName"

}

class ObjectFieldMemoryLocation(
    strategy: ManagedStrategy,
    clazz: Class<*>,
    override val objID: ObjectID,
    val className: String,
    val fieldName: String,
) : MemoryLocation {

    init {
        check(objID != NULL_OBJECT_ID)
    }

    val simpleClassName: String = clazz.simpleName

    private val field: Field by lazy {
        val resolvedClass = resolveClass(strategy, clazz, className = className)
        resolveField(resolvedClass, className, fieldName)
            .apply { isAccessible = true }
    }

    override fun read(valueMapper: ValueMapper): Any? {
        return field.get(valueMapper(objID)?.unwrap())
    }

    override fun write(valueMapper: ValueMapper, value: Any?) {
        field.set(valueMapper(objID)?.unwrap(), value)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other)
            return true
        return (other is ObjectFieldMemoryLocation)
                && (objID == other.objID)
                && (className == other.className)
                && (fieldName == other.fieldName)
    }

    override fun hashCode(): Int {
        var result = objID.hashCode()
        result = 31 * result + className.hashCode()
        result = 31 * result + fieldName.hashCode()
        return result
    }

    override fun toString(): String {
        return "${objRepr(simpleClassName, objID)}::$fieldName"
    }

}

class ArrayElementMemoryLocation(
    strategy: ManagedStrategy,
    clazz: Class<*>,
    override val objID: ObjectID,
    val index: Int,
) : MemoryLocation {

    init {
        check(objID != NULL_OBJECT_ID)
    }

    val className: String = clazz.simpleName

    private val isPlainArray = clazz.isArray

    private val getMethod: Method? by lazy {
        if (isPlainArray) {
            return@lazy null
        }
        val resolvedClass = resolveClass(strategy, clazz)
        return@lazy resolvedClass.methods
            // TODO: can we use getOpaque() for atomic arrays here?
            .first { it.name == "get" }
            .apply { isAccessible = true }
    }

    private val setMethod by lazy {
        if (isPlainArray) {
            return@lazy null
        }
        val resolvedClass = resolveClass(strategy, clazz)
        return@lazy resolvedClass.methods
            // TODO: can we use setOpaque() for atomic arrays here?
            .first { it.name == "set" }
            .apply { isAccessible = true }
    }

    override fun read(valueMapper: ValueMapper): Any? {
        if (isPlainArray) {
            return ReflectArray.get(valueMapper(objID)?.unwrap(), index)
        }
        return getMethod!!.invoke(valueMapper(objID)?.unwrap(), index)
    }

    override fun write(valueMapper: ValueMapper, value: Any?) {
        if (isPlainArray) {
            ReflectArray.set(valueMapper(objID)?.unwrap(), index, value)
            return
        }
        setMethod!!.invoke(valueMapper(objID)?.unwrap(), index, value)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other)
            return true
        return (other is ArrayElementMemoryLocation)
                && (objID == other.objID)
                && (index == other.index)
    }

    override fun hashCode(): Int {
        var result = objID.hashCode()
        result = 31 * result + index
        return result
    }

    override fun toString(): String {
        return "${objRepr(className, objID)}[$index]"
    }

}

class AtomicPrimitiveMemoryLocation(
    strategy: ManagedStrategy,
    clazz: Class<*>,
    override val objID: ObjectID,
) : MemoryLocation {

    init {
        require(objID != NULL_OBJECT_ID)
    }

    val className: String = clazz.simpleName

    private val getMethod by lazy {
        // TODO: can we use getOpaque() here?
        resolveClass(strategy, clazz).methods
            .first { it.name == "get" }
            .apply { isAccessible = true }
    }

    private val setMethod by lazy {
        // TODO: can we use setOpaque() here?
        resolveClass(strategy, clazz).methods
            .first { it.name == "set" }
            .apply { isAccessible = true }
    }

    override fun read(valueMapper: ValueMapper): Any? {
        return getMethod.invoke(valueMapper(objID)?.unwrap())
    }

    override fun write(valueMapper: ValueMapper, value: Any?) {
        setMethod.invoke(valueMapper(objID)?.unwrap(), value)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other)
            return true
        return (other is AtomicPrimitiveMemoryLocation)
                && (objID == other.objID)
    }

    override fun hashCode(): Int {
        return objID.hashCode()
    }

    override fun toString(): String {
        check(objID != NULL_OBJECT_ID)
        return objRepr(className, objID)
    }

}

internal fun objRepr(className: String, objID: ObjectID): String {
    return when (objID) {
        NULL_OBJECT_ID -> "null"
        else -> "$className$objID"
    }
}

private fun matchClassName(clazz: Class<*>, className: String) =
    clazz.name.endsWith(className) || (clazz.canonicalName?.endsWith(className) ?: false)

private fun resolveClass(strategy: ManagedStrategy, clazz: Class<*>? = null, className: String? = null): Class<*> {
    if (className == null) {
        check(clazz != null)
        return clazz
    }
    if (clazz == null) {
        return strategy.classLoader.loadClass(className)
    }
    if (matchClassName(clazz, className)) {
        return clazz
    }
    var superClass = clazz.superclass
    while (superClass != null) {
        if (matchClassName(superClass, className))
            return superClass
        superClass = superClass.superclass
    }
    throw IllegalStateException("Cannot find class $className for object of class ${clazz.name}!")
}

private fun resolveField(clazz: Class<*>, className: String, fieldName: String): Field {
    var currentClass: Class<*>? = clazz
    do {
        currentClass?.fields?.firstOrNull { it.name == fieldName }?.let { return it }
        currentClass?.declaredFields?.firstOrNull { it.name == fieldName }?.let { return it }
        currentClass = currentClass?.superclass
    } while (currentClass != null)
    throw IllegalStateException("Cannot find field $className::$fieldName for class $clazz!")
}