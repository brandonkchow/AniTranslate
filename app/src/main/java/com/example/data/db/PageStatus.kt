package com.example.data.db

enum class PageStatus(val displayName: String) {
    QUEUED("Queued"),
    DETECTING("Detecting"),
    TRANSLATING("Translating"),
    WIPING("Wiping"),
    RENDERING("Rendering"),
    WAITING("Waiting"),
    DONE("Done"),
    FAILED("Failed");

    val isTerminal: Boolean
        get() = this == DONE || this == FAILED

    val isInProgress: Boolean
        get() = this == DETECTING || this == TRANSLATING || this == WIPING || this == RENDERING
}
