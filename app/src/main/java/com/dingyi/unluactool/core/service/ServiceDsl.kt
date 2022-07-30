package com.dingyi.unluactool.core.service


inline fun <reified T> ServiceRegistry.get(): T {
    return get(T::class.java)
}

inline fun <reified T> ServiceRegistry.find(): T {
    return find(T::class.java) as T
}