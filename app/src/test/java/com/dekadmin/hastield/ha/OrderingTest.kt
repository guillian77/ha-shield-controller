package com.dekadmin.hastield.ha

import org.junit.Assert.assertEquals
import org.junit.Test

class OrderingTest {

    @Test
    fun `normalize conserve l'ordre connu et retire les non sélectionnées`() {
        val order = listOf("light.a", "light.b", "light.c")
        val selected = setOf("light.c", "light.a")

        assertEquals(listOf("light.a", "light.c"), Ordering.normalize(order, selected))
    }

    @Test
    fun `normalize ajoute les nouvelles entités à la fin, triées`() {
        val order = listOf("light.a")
        val selected = setOf("light.a", "light.z", "light.b")

        assertEquals(listOf("light.a", "light.b", "light.z"), Ordering.normalize(order, selected))
    }

    @Test
    fun `normalize déduplique et ignore les entités inconnues`() {
        val order = listOf("light.a", "light.a", "light.gone")
        val selected = setOf("light.a")

        assertEquals(listOf("light.a"), Ordering.normalize(order, selected))
    }

    @Test
    fun `move monte et descend d'une position`() {
        val order = listOf("a", "b", "c")

        assertEquals(listOf("b", "a", "c"), Ordering.move(order, "b", -1))
        assertEquals(listOf("a", "c", "b"), Ordering.move(order, "b", 1))
    }

    @Test
    fun `move ne fait rien aux bornes ou sur une entité absente`() {
        val order = listOf("a", "b", "c")

        assertEquals(order, Ordering.move(order, "a", -1))
        assertEquals(order, Ordering.move(order, "c", 1))
        assertEquals(order, Ordering.move(order, "zzz", 1))
    }

    @Test
    fun `positionOf renvoie une position 1-based`() {
        val order = listOf("a", "b", "c")

        assertEquals(1, Ordering.positionOf(order, "a"))
        assertEquals(3, Ordering.positionOf(order, "c"))
        assertEquals(0, Ordering.positionOf(order, "absent"))
    }

    @Test
    fun `une séquence de déplacements reste cohérente`() {
        var order = Ordering.normalize(emptyList(), setOf("a", "b", "c"))
        order = Ordering.move(order, "c", -1)
        order = Ordering.move(order, "c", -1)

        assertEquals(listOf("c", "a", "b"), order)
        assertEquals(listOf("c", "a", "b"), Ordering.normalize(order, setOf("a", "b", "c")))
    }
}
