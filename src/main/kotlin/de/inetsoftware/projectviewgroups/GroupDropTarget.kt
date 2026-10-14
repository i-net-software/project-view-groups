package de.inetsoftware.projectviewgroups

import com.intellij.ide.dnd.DnDEvent
import com.intellij.ide.dnd.DnDManager
import com.intellij.ide.dnd.DnDNativeTarget
import com.intellij.ide.dnd.DnDTarget
import com.intellij.ide.dnd.TransferableWrapper
import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.projectView.impl.AbstractProjectViewPane
import com.intellij.ide.projectView.impl.ProjectViewListener
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.awt.RelativeRectangle
import com.intellij.util.ui.tree.TreeUtil
import java.awt.Image
import java.awt.Point
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.tree.TreePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles drops of projects onto group nodes and passes everything else to the view's own drop target
 * (moving or copying files). The Project tool window has no extension point for this, so the drop target
 * of every pane's tree is wrapped.
 */
class GroupDropTarget(private val project: Project, private val tree: JTree, private val delegate: DnDTarget?) : DnDNativeTarget {

    private class Plan(val selection: GroupSelection, val targetId: String?, val text: String, val bounds: Rectangle)

    override fun update(event: DnDEvent): Boolean {
        val plan = plan(event) ?: return delegate?.update(event) ?: run { event.setDropPossible(false, ""); false }
        event.setHighlighting(RelativeRectangle(tree, plan.bounds), DnDEvent.DropTargetHighlightingType.RECTANGLE)
        event.setDropPossible(true, plan.text)
        return false
    }

    override fun drop(event: DnDEvent) {
        val plan = plan(event) ?: return delegate?.drop(event) ?: Unit
        GroupActions.move(project, plan.selection, plan.targetId)
    }

    override fun cleanUpOnLeave() {
        delegate?.cleanUpOnLeave()
    }

    override fun updateDraggedImage(image: Image?, dropPoint: Point?, imageOffset: Point?) {
        delegate?.updateDraggedImage(image, dropPoint, imageOffset)
    }

    /** A group move if the dragged nodes are projects or groups of this tree and the target is a group or the container. */
    private fun plan(event: DnDEvent): Plan? {
        val sources = (event.attachedObject as? TransferableWrapper)?.treePaths ?: return null
        val point = event.point ?: return null
        val targetPath = tree.getClosestPathForLocation(point.x, point.y) ?: return null
        val bounds = tree.getPathBounds(targetPath)?.takeIf { point.y >= it.y && point.y < it.y + it.height } ?: return null
        val target = node(targetPath) ?: return null
        val nodes = sources.mapNotNull(::node)
        if (nodes.isEmpty() || nodes.size != sources.size) return null

        val selection = runReadActionBlocking {
            if (!nodes.all { it is GroupNode || ProjectNodes.isProjectNode(it) }) return@runReadActionBlocking null
            GroupSelection(nodes.filterIsInstance<GroupNode>().map { it.groupId }, nodes.filter { it !is GroupNode }.mapNotNull(ProjectNodes::keyOf))
        } ?: return null
        val model = GroupService.getInstance(project).model
        return when {
            target is GroupNode -> {
                if (selection.groups.any { model.isSelfOrAncestor(it, target.groupId) }) return null
                if (selection.groups.isEmpty() && selection.projects.all { model.groupOf(it) == target.groupId }) return null
                Plan(selection, target.groupId, "Move to group \"${target.groupName}\"", bounds)
            }
            ProjectNodes.isContainer(target) && target.parent != null -> {
                // A visible container (the workspace node): back to the top level
                if (selection.projects.none { model.groupOf(it) != null } && selection.groups.none { model[it]?.parentId != null }) return null
                Plan(selection, null, "Remove from group", bounds)
            }
            else -> null
        }
    }

    private fun node(path: TreePath): AbstractTreeNode<*>? = TreeUtil.getUserObject(AbstractTreeNode::class.java, path.lastPathComponent)

    companion object {
        /** Client property under which DnDManager keeps a component's drop target. */
        private const val DND_TARGET_KEY = "DnD Target"

        fun install(project: Project, pane: AbstractProjectViewPane?) {
            val tree = pane?.tree as? JComponent ?: return
            val existing = tree.getClientProperty(DND_TARGET_KEY) as? DnDTarget
            if (existing is GroupDropTarget) return
            DnDManager.getInstance().registerTarget(GroupDropTarget(project, tree as JTree, existing), tree, pane)
        }

        fun installAll(project: Project) {
            if (project.isDisposed) return
            val view = ProjectView.getInstance(project)
            for (id in view.paneIds) install(project, view.getProjectViewPaneById(id))
        }
    }
}

/** Wraps the drop targets of panes created later (switching views) and of the initial pane. */
class GroupDropInstaller(private val project: Project) : ProjectViewListener, ToolWindowManagerListener {
    override fun paneShown(current: AbstractProjectViewPane, previous: AbstractProjectViewPane?) {
        GroupDropTarget.install(project, current)
    }

    override fun toolWindowShown(toolWindow: ToolWindow) {
        if (toolWindow.id == ToolWindowId.PROJECT_VIEW) GroupDropTarget.installAll(project)
    }
}

class GroupStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        withContext(Dispatchers.EDT) { GroupDropTarget.installAll(project) }
    }
}
