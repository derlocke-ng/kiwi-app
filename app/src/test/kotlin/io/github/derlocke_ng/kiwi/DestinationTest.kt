package io.github.derlocke_ng.kiwi

import io.github.derlocke_ng.kiwi.ui.Destination
import org.junit.Assert.assertEquals
import org.junit.Test

class DestinationTest {
    @Test
    fun bottomBarHasTheFourDestinationsInOrder() {
        assertEquals(
            listOf("home", "services", "machines", "more"),
            Destination.entries.map { it.route },
        )
    }

    @Test
    fun routesAreUnique() {
        val routes = Destination.entries.map { it.route }
        assertEquals(routes.size, routes.toSet().size)
    }
}
