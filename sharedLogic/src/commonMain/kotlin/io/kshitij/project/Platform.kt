package io.kshitij.project

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform