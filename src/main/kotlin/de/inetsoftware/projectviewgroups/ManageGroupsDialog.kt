package de.inetsoftware.projectviewgroups

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CheckBoxList
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * Edits the groups of a project: the group tree on the left, the projects of the selected group on the right.
 * Works on a copy of the model that is applied with OK.
 */
class ManageGroupsDialog(private val project: Project, private val initialGroup: String?) : DialogWrapper(project) {

    private val working = GroupService.getInstance(project).model.copy()
    private val candidates = ProjectNodes.candidates(project, working)

    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode())
    private val tree = Tree(treeModel)
    private val projectList = object : CheckBoxList<String>() {
        override fun getSecondaryText(index: Int): String? {
            val key = getItemAt(index) ?: return null
            val groupId = working.groupOf(key) ?: return null
            return if (groupId == selectedGroup()) null else working.path(groupId).joinToString(" / ")
        }
    }
    private val filter = SearchTextField(false)
    private val projectsLabel = JBLabel()

    init {
        title = "Project Groups"
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
                val group = ((value as? DefaultMutableTreeNode)?.userObject as? String)?.let(working::get) ?: return
                icon = AllIcons.Nodes.ModuleGroup
                append(group.name)
                if (group.members.isNotEmpty()) append("  ${group.members.size}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        tree.addTreeSelectionListener { refreshProjects() }
        projectList.setCheckBoxListListener { index, checked ->
            val key = projectList.getItemAt(index) ?: return@setCheckBoxListListener
            working.assign(listOf(key), if (checked) selectedGroup() else null)
            tree.repaint()
            projectList.repaint()
        }
        filter.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refreshProjects()
        })
        rebuildTree(initialGroup)
        init()
    }

    override fun createCenterPanel(): JComponent {
        val groupsPanel = ToolbarDecorator.createDecorator(tree)
            .setAddAction { addGroup() }
            .setAddActionName("New Group (nested in the selected one)")
            .setEditAction { renameGroup() }
            .setEditActionName("Rename Group")
            .setRemoveAction { deleteGroup() }
            .setRemoveActionName("Delete Group")
            .setRemoveActionUpdater { selectedGroup() != null }
            .setEditActionUpdater { selectedGroup() != null }
            .addExtraAction(object : DumbAwareAction("New Top-Level Group", null, AllIcons.Nodes.ModuleGroup) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = addGroup(null)
            })
            .addExtraAction(object : DumbAwareAction("Move to Top Level", null, AllIcons.Actions.MoveUp) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = selectedGroup()?.let { working[it]?.parentId } != null
                }
                override fun actionPerformed(e: AnActionEvent) {
                    val id = selectedGroup() ?: return
                    working.move(id, null)
                    rebuildTree(id)
                }
            })
            .createPanel()

        val right = JPanel(BorderLayout(0, JBUI.scale(4)))
        val header = JPanel(BorderLayout(0, JBUI.scale(4)))
        header.add(projectsLabel, BorderLayout.NORTH)
        header.add(filter, BorderLayout.CENTER)
        right.add(header, BorderLayout.NORTH)
        right.add(JBScrollPane(projectList), BorderLayout.CENTER)

        return JBSplitter(false, 0.4f).apply {
            firstComponent = groupsPanel
            secondComponent = right
            preferredSize = JBUI.size(760, 480)
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = tree

    override fun doOKAction() {
        GroupService.getInstance(project).replace(working)
        super.doOKAction()
    }

    private fun selectedGroup(): String? = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? String

    private fun addGroup(parentId: String? = selectedGroup()) {
        val name = askName("New Project Group", "") ?: return
        val group = working.create(name, parentId)
        rebuildTree(group.id)
    }

    private fun renameGroup() {
        val id = selectedGroup() ?: return
        val name = askName("Rename Project Group", working[id]?.name ?: "") ?: return
        working.rename(id, name)
        rebuildTree(id)
    }

    private fun deleteGroup() {
        val id = selectedGroup() ?: return
        val parent = working[id]?.parentId
        working.delete(id)
        rebuildTree(parent)
    }

    private fun askName(title: String, initial: String): String? =
        Messages.showInputDialog(tree, "Name:", title, AllIcons.Nodes.ModuleGroup, initial, object : InputValidator {
            override fun checkInput(inputString: String?) = !inputString.isNullOrBlank()
            override fun canClose(inputString: String?) = checkInput(inputString)
        })

    private fun rebuildTree(select: String?) {
        val root = DefaultMutableTreeNode()
        fun add(parent: DefaultMutableTreeNode, parentId: String?) {
            for (group in working.children(parentId)) {
                val node = DefaultMutableTreeNode(group.id)
                parent.add(node)
                add(node, group.id)
            }
        }
        add(root, null)
        treeModel.setRoot(root)
        TreeUtil.expandAll(tree)
        val target = TreeUtil.treeNodeTraverser(root).find { (it as DefaultMutableTreeNode).userObject == select } as? DefaultMutableTreeNode
        if (target != null) tree.selectionPath = TreePath(target.path) else TreeUtil.selectFirstNode(tree)
        refreshProjects()
    }

    private fun refreshProjects() {
        val groupId = selectedGroup()
        val text = filter.text.trim()
        projectList.clear()
        if (groupId == null) {
            projectsLabel.text = if (working.isEmpty) "Create a group with +" else "Select a group"
            projectList.isEnabled = false
            return
        }
        projectList.isEnabled = true
        projectsLabel.text = "Projects in \"${working.path(groupId).joinToString(" / ")}\":"
        candidates
            .filter { text.isEmpty() || ProjectNodes.displayName(it).contains(text, ignoreCase = true) }
            .forEach { projectList.addItem(it, ProjectNodes.displayName(it), working.groupOf(it) == groupId) }
    }
}
