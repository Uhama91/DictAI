package com.kafkasl.phonewhisper

import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Test

class InjectionGatewayTest {
    private var registeredByTest: InjectionController? = null

    @After
    fun clearControllerCreatedByThisTest() {
        registeredByTest?.let(InjectionGateway::unregister)
    }

    @Test
    fun `latest registered controller is returned`() {
        val controller = FakeController()
        registeredByTest = controller

        InjectionGateway.register(controller)

        assertSame(controller, InjectionGateway.current())
    }

    @Test
    fun `unregistering stale controller keeps replacement`() {
        val stale = FakeController()
        val replacement = FakeController()
        registeredByTest = replacement

        InjectionGateway.register(stale)
        InjectionGateway.register(replacement)
        InjectionGateway.unregister(stale)

        assertSame(replacement, InjectionGateway.current())
    }

    @Test
    fun `unregistering current controller clears gateway`() {
        val controller = FakeController()
        registeredByTest = controller

        InjectionGateway.register(controller)
        InjectionGateway.unregister(controller)

        assertNull(InjectionGateway.current())
    }

    private class FakeController : InjectionController {
        override fun inject(text: String): InjectionResult = InjectionResult.Failed
    }
}
