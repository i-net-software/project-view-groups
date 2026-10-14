package de.inetsoftware.projectviewgroups

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.assertEquals
import org.junit.Test

/** The state survives the IDE's XML serialization, as used for the workspace file. */
class GroupsStateXmlTest {

    @Test
    fun xmlRoundTrip() {
        val model = GroupModel().apply {
            create("Reporting", id = "rep")
            create("Server", parentId = "rep", id = "srv")
            assign(listOf("/ws/report-core"), "rep")
            assign(listOf("/ws/report-server", "/ws/report-web"), "srv")
        }
        val element = XmlSerializer.serialize(model.toState())
        val xml = JDOMUtil.writeElement(element)
        val restored = GroupModel.fromState(XmlSerializer.deserialize(JDOMUtil.load(xml), GroupsState::class.java))

        assertEquals(listOf("Reporting" to 0, "Server" to 1), restored.flatten().map { it.first.name to it.second })
        assertEquals(listOf("/ws/report-server", "/ws/report-web"), restored["srv"]!!.members)
        assertEquals("rep", restored.groupOf("/ws/report-core"))
    }
}
