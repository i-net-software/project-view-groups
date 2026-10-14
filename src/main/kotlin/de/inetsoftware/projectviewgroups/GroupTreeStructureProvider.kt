package de.inetsoftware.projectviewgroups

import com.intellij.ide.DeleteProvider
import com.intellij.ide.projectView.TreeStructureProvider
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project

/**
 * Moves the projects of every Project tool window view (Project, Packages, Project Files, …) into their groups.
 * Registered after the Multi-Project Workspace provider, so with that plugin the projects below the workspace
 * node are grouped.
 */
class GroupTreeStructureProvider(private val project: Project) : TreeStructureProvider, DumbAware {

    override fun modify(
        parent: AbstractTreeNode<*>,
        children: Collection<AbstractTreeNode<*>>,
        settings: ViewSettings?,
    ): Collection<AbstractTreeNode<*>> {
        if (parent is GroupNode || !ProjectNodes.isContainer(parent)) return children
        // The workspace node holds the projects; they are grouped one level down
        if (children.any(ProjectNodes::isWorkspaceNode)) return children
        val model = GroupService.getInstance(project).model
        if (model.isEmpty) return children

        val members = HashMap<String, MutableList<AbstractTreeNode<*>>>()
        val result = ArrayList<AbstractTreeNode<*>>()
        for (child in children) {
            val groupId = ProjectNodes.keyOf(child)?.let(model::groupOf)
            if (groupId != null) members.getOrPut(groupId, ::ArrayList).add(child) else result.add(child)
        }
        val content = GroupContent(model, members)
        content.visibleChildren(null).mapTo(result) { GroupNode(project, it.id, settings, content) }
        return result
    }

    override fun uiDataSnapshot(sink: DataSink, selection: Collection<AbstractTreeNode<*>>) {
        if (selection.isEmpty() || !selection.all { it is GroupNode }) return
        val ids = selection.map { (it as GroupNode).groupId }
        sink[PlatformDataKeys.DELETE_ELEMENT_PROVIDER] = object : DeleteProvider {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun canDeleteElement(dataContext: DataContext) = true
            override fun deleteElement(dataContext: DataContext) = GroupActions.deleteGroups(project, ids)
        }
    }
}
