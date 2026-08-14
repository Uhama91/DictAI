package com.kafkasl.phonewhisper

import java.util.concurrent.atomic.AtomicReference

/** Process-local access point for the currently connected accessibility service. */
internal object InjectionGateway {
    private val currentController = AtomicReference<InjectionController?>(null)

    fun register(controller: InjectionController) {
        currentController.set(controller)
    }

    fun unregister(controller: InjectionController) {
        currentController.compareAndSet(controller, null)
    }

    fun current(): InjectionController? = currentController.get()
}
