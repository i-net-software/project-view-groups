package de.inetsoftware.projectviewgroups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupModelTest {

    private fun model() = GroupModel().apply {
        create("Reporting", id = "rep")
        create("Server", parentId = "rep", id = "srv")
        create("Clear", id = "clr")
        assign(listOf("/ws/report-core", "/ws/report-api"), "rep")
        assign(listOf("/ws/report-server"), "srv")
    }

    @Test
    fun childrenAreSortedByName() {
        val model = model()
        model.create("apps", id = "app")
        assertEquals(listOf("apps", "Clear", "Reporting"), model.children(null).map { it.name })
        assertEquals(listOf("Server"), model.children("rep").map { it.name })
    }

    @Test
    fun projectBelongsToAtMostOneGroup() {
        val model = model()
        model.assign(listOf("/ws/report-api"), "clr")
        assertEquals("clr", model.groupOf("/ws/report-api"))
        assertEquals(listOf("/ws/report-core"), model["rep"]!!.members)
        model.assign(listOf("/ws/report-api"), null)
        assertNull(model.groupOf("/ws/report-api"))
    }

    @Test
    fun deleteMovesContentUp() {
        val model = model()
        model.delete("srv")
        assertEquals("rep", model.groupOf("/ws/report-server"))
        model.delete("rep")
        assertNull(model.groupOf("/ws/report-core"))
        assertEquals(listOf("Clear"), model.children(null).map { it.name })
    }

    @Test
    fun deleteMovesSubgroupsUp() {
        val model = model()
        model.delete("rep")
        assertNull(model["srv"]!!.parentId)
        assertEquals("srv", model.groupOf("/ws/report-server"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun moveIntoOwnSubgroupIsRejected() {
        model().move("rep", "srv")
    }

    @Test
    fun moveGroup() {
        val model = model()
        model.move("srv", "clr")
        assertEquals(listOf("Clear", "Server"), model.path("srv"))
        model.move("srv", null)
        assertEquals(listOf("Server"), model.path("srv"))
    }

    @Test
    fun ancestorCheck() {
        val model = model()
        assertTrue(model.isSelfOrAncestor("rep", "srv"))
        assertTrue(model.isSelfOrAncestor("srv", "srv"))
        assertFalse(model.isSelfOrAncestor("srv", "rep"))
        assertFalse(model.isSelfOrAncestor("clr", "srv"))
    }

    @Test
    fun membersDeep() {
        val model = model()
        model.assign(listOf("/ws/report-core", "/ws/report-api"), null)
        assertTrue(model.hasMembersDeep("rep"))
        assertFalse(model.hasMembersDeep("clr"))
    }

    @Test
    fun flattenIsDepthFirst() {
        assertEquals(listOf("Clear" to 0, "Reporting" to 0, "Server" to 1), model().flatten().map { it.first.name to it.second })
    }

    @Test
    fun renameTrims() {
        val model = model()
        model.rename("clr", "  i-net Clear ")
        assertEquals("i-net Clear", model["clr"]!!.name)
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankNameIsRejected() {
        model().create(" ")
    }

    @Test
    fun copyIsIndependent() {
        val model = model()
        val copy = model.copy()
        copy.rename("rep", "Other")
        copy.assign(listOf("/ws/x"), "clr")
        assertEquals("Reporting", model["rep"]!!.name)
        assertNull(model.groupOf("/ws/x"))
    }

    @Test
    fun stateRoundTrip() {
        val restored = GroupModel.fromState(model().toState())
        assertEquals(listOf("Clear" to 0, "Reporting" to 0, "Server" to 1), restored.flatten().map { it.first.name to it.second })
        assertEquals("srv", restored.groupOf("/ws/report-server"))
        assertEquals(listOf("/ws/report-core", "/ws/report-api"), restored["rep"]!!.members)
    }

    @Test
    fun brokenStateIsRepaired() {
        val state = GroupsState().apply {
            groups = mutableListOf(
                bean("a", "A", parent = "b", "/p1"),
                bean("b", "B", parent = "a", "/p1", "/p2"),
                bean("c", "C", parent = "missing"),
                bean("a", "Duplicate"),
                bean("", "No id"),
            )
        }
        val model = GroupModel.fromState(state)
        assertEquals(listOf("A", "B", "C"), model.all().map { it.name })
        assertNull(model["c"]!!.parentId)
        // the cycle a <-> b is broken, one of them is top-level
        assertTrue(model.all().any { it.parentId == null && it.id in setOf("a", "b") })
        assertEquals("a", model.groupOf("/p1"))
        assertEquals(listOf("/p2"), model["b"]!!.members)
    }

    private fun bean(id: String, name: String, parent: String? = null, vararg projects: String) = GroupBean().also {
        it.id = id
        it.name = name
        it.parent = parent
        it.projects = projects.toMutableList()
    }
}
