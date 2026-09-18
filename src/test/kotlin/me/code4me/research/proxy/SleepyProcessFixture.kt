package me.code4me.research.proxy

/**
 * Test fixture: a long-running process used to verify real proxy teardown.
 *
 * Launched in a child JVM by `ProxyProcessLauncherTest` (portable, no shell).
 */
fun main() {
    Thread.sleep(60_000)
}
