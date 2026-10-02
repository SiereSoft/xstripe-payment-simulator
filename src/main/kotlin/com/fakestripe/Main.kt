package com.fakestripe

/** Process entry point: start both listeners from the environment and run until stopped. */
fun main() {
    val servers = startServers(System.getenv())
    Runtime.getRuntime().addShutdownHook(Thread { servers.stop() })
    servers.awaitStop()
}
