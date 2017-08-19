package com.redstoner.plots.util

inline fun String.toIntOr(block: (String) -> Int): Int = toIntOrNull() ?: block(this)