package com.kafkasl.phonewhisper

/** Contrat exposé par le service d'accessibilité au OverlayService. */
interface InjectionController {
    fun inject(text: String): Boolean
}
