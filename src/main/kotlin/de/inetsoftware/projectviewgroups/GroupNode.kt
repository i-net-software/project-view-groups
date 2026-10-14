package de.inetsoftware.projectviewgroups

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/** Tree value of a [GroupNode]; node equality (and the expanded state) is based on the group id. */
data class GroupId(val id: String)

/** The grouped children of one container node in one view, resolved against a model snapshot. */
class GroupContent(val model: GroupModel, private val members: Map<String, List<AbstractTreeNode<*>>>) {

    fun members(groupId: String): List<AbstractTreeNode<*>> = members[groupId].orEmpty()

    /** Shown if a project of this view is in it, or if it has no projects at all yet (so it can be filled). */
    fun isVisible(groupId: String): Boolean = hasNodesDeep(groupId) || !model.hasMembersDeep(groupId)

    private fun hasNodesDeep(groupId: String): Boolean =
        members(groupId).isNotEmpty() || model.children(groupId).any { hasNodesDeep(it.id) }

    fun visibleChildren(groupId: String?) = model.children(groupId).filter { isVisible(it.id) }
}

class GroupNode(
    project: Project,
    groupId: String,
    settings: ViewSettings?,
    val content: GroupContent,
) : ProjectViewNode<GroupId>(project, GroupId(groupId), settings) {

    val groupId: String get() = value.id

    val groupName: String get() = content.model[groupId]?.name ?: ""

    override fun getChildren(): Collection<AbstractTreeNode<*>> {
        val result = ArrayList<AbstractTreeNode<*>>()
        content.visibleChildren(groupId).mapTo(result) { GroupNode(myProject, it.id, settings, content) }
        result.addAll(content.members(groupId))
        return result
    }

    override fun contains(file: VirtualFile): Boolean =
        content.members(groupId).any { (it as? ProjectViewNode<*>)?.contains(file) == true } ||
            content.visibleChildren(groupId).any { GroupNode(myProject, it.id, settings, content).contains(file) }

    override fun update(presentation: PresentationData) {
        presentation.presentableText = groupName
        presentation.setIcon(AllIcons.Nodes.ModuleGroup)
        val count = countDeep(groupId)
        if (count > 0) presentation.locationString = if (count == 1) "1 project" else "$count projects"
    }

    private fun countDeep(id: String): Int = content.members(id).size + content.model.children(id).sumOf { countDeep(it.id) }

    override fun isAlwaysShowPlus(): Boolean = content.members(groupId).isNotEmpty() || content.visibleChildren(groupId).isNotEmpty()

    override fun canRepresent(element: Any?): Boolean = element == value

    override fun getTypeSortWeight(sortByType: Boolean): Int = 0

    override fun getWeight(): Int = 0

    override fun getTitle(): String = content.model.path(groupId).joinToString(" / ")

    override fun toString(): String = "Group $groupName"
}
