package de.inetsoftware.projectviewgroups

import com.intellij.icons.AllIcons
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.rename.RenameHandler

/** What is selected in the Project tool window, as far as groups are concerned. */
class GroupSelection(val groups: List<String>, val projects: List<String>) {
    val isEmpty: Boolean get() = groups.isEmpty() && projects.isEmpty()

    companion object {
        fun of(dataContext: DataContext): GroupSelection = of(PlatformCoreDataKeys.SELECTED_ITEMS.getData(dataContext))

        fun of(e: AnActionEvent): GroupSelection = of(e.getData(PlatformCoreDataKeys.SELECTED_ITEMS))

        private fun of(items: Array<out Any>?): GroupSelection {
            val nodes = items?.filterIsInstance<AbstractTreeNode<*>>().orEmpty()
            val groups = nodes.filterIsInstance<GroupNode>().map { it.groupId }
            val projects = runReadActionBlocking {
                nodes.filter(ProjectNodes::isProjectNode).mapNotNull(ProjectNodes::keyOf)
            }
            return GroupSelection(groups, projects)
        }
    }
}

object GroupActions {

    /** Asks for a name and creates a group below [parentId] holding [projects]; returns its id. */
    fun createGroup(project: Project, parentId: String?, projects: List<String> = emptyList()): String? {
        val service = GroupService.getInstance(project)
        val parentPath = parentId?.let { service.model.path(it).joinToString(" / ") }
        val message = if (parentPath != null) "Name of the new group in \"$parentPath\":" else "Name of the new group:"
        val name = Messages.showInputDialog(project, message, "New Project Group", AllIcons.Nodes.ModuleGroup, "", NonBlank)
            ?: return null
        var id: String? = null
        service.update { model ->
            val group = model.create(name, parentId?.takeIf { model[it] != null })
            if (projects.isNotEmpty()) model.assign(projects, group.id)
            id = group.id
        }
        return id
    }

    fun renameGroup(project: Project, groupId: String) {
        val service = GroupService.getInstance(project)
        val group = service.model[groupId] ?: return
        val name = Messages.showInputDialog(project, "New name:", "Rename Project Group", AllIcons.Nodes.ModuleGroup, group.name, NonBlank)
            ?: return
        service.update { if (it[groupId] != null) it.rename(groupId, name) }
    }

    fun deleteGroups(project: Project, groupIds: List<String>) {
        val service = GroupService.getInstance(project)
        val names = groupIds.mapNotNull { service.model[it]?.name }
        if (names.isEmpty()) return
        val what = if (names.size == 1) "group \"${names[0]}\"" else "${names.size} groups"
        val answer = Messages.showYesNoDialog(
            project,
            "Delete $what? Its projects and subgroups move up one level; no files are touched.",
            "Delete Project Group",
            "Delete",
            Messages.getCancelButton(),
            Messages.getQuestionIcon(),
        )
        if (answer != Messages.YES) return
        service.update { model -> groupIds.forEach(model::delete) }
    }

    /** Moves projects and groups into [targetId], or to the top level for `null`. Skips moves that would create a cycle. */
    fun move(project: Project, selection: GroupSelection, targetId: String?) {
        GroupService.getInstance(project).update { model ->
            if (targetId != null && model[targetId] == null) return@update
            model.assign(selection.projects, targetId)
            for (id in selection.groups) {
                if (model[id] != null && (targetId == null || !model.isSelfOrAncestor(id, targetId))) model.move(id, targetId)
            }
        }
    }

    private object NonBlank : InputValidator {
        override fun checkInput(inputString: String?) = !inputString.isNullOrBlank()
        override fun canClose(inputString: String?) = checkInput(inputString)
    }
}

/** "Project Group…" in the Project tool window's + menu and in File | New; nested if a group is selected. */
class NewGroupAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val groups = GroupSelection.of(e).groups
        GroupActions.createGroup(project, groups.singleOrNull())
    }
}

/** Context menu: "Move to Group" with all groups, a new group and the top level. */
class MoveToGroupActionGroup : DefaultActionGroup() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isPopupGroup = true
        e.presentation.isEnabledAndVisible = e.project != null && !GroupSelection.of(e).isEmpty
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project ?: return emptyArray()
        val selection = GroupSelection.of(e)
        if (selection.isEmpty) return emptyArray()
        val model = GroupService.getInstance(project).model
        val actions = ArrayList<AnAction>()
        actions.add(object : DumbAwareAction("New Group…", null, AllIcons.General.Add) {
            override fun actionPerformed(e: AnActionEvent) {
                val parent = selection.groups.firstOrNull()?.let { model[it]?.parentId }
                    ?: selection.projects.map(model::groupOf).distinct().singleOrNull()
                val id = GroupActions.createGroup(project, parent, selection.projects) ?: return
                if (selection.groups.isNotEmpty()) GroupActions.move(project, GroupSelection(selection.groups, emptyList()), id)
            }
        })
        val flat = model.flatten().filter { (group, _) -> selection.groups.none { model.isSelfOrAncestor(it, group.id) } }
        if (flat.isNotEmpty()) actions.add(Separator.getInstance())
        for ((group, depth) in flat) {
            val text = "    ".repeat(depth) + group.name
            actions.add(object : DumbAwareAction(text, null, AllIcons.Nodes.ModuleGroup) {
                override fun actionPerformed(e: AnActionEvent) = GroupActions.move(project, selection, group.id)
            })
        }
        return actions.toTypedArray()
    }
}

/** Moves the selected projects and groups to the top level. */
class RemoveFromGroupAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val selection = GroupSelection.of(e)
        val model = project?.let { GroupService.getInstance(it).model }
        e.presentation.isEnabledAndVisible = model != null &&
            (selection.projects.any { model.groupOf(it) != null } || selection.groups.any { model[it]?.parentId != null })
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        GroupActions.move(project, GroupSelection.of(e), null)
    }
}

class RenameGroupAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val selection = GroupSelection.of(e)
        e.presentation.isEnabledAndVisible = e.project != null && selection.groups.size == 1 && selection.projects.isEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        GroupSelection.of(e).groups.singleOrNull()?.let { GroupActions.renameGroup(project, it) }
    }
}

class DeleteGroupAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val selection = GroupSelection.of(e)
        e.presentation.isEnabledAndVisible = e.project != null && selection.groups.isNotEmpty() && selection.projects.isEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        GroupActions.deleteGroups(project, GroupSelection.of(e).groups)
    }
}

class ManageGroupsAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ManageGroupsDialog(project, GroupSelection.of(e).groups.firstOrNull()).show()
    }
}

/** Shift+F6 on a group node. */
class GroupRenameHandler : RenameHandler {
    override fun isAvailableOnDataContext(dataContext: DataContext): Boolean {
        val selection = GroupSelection.of(dataContext)
        return selection.groups.size == 1 && selection.projects.isEmpty()
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?, dataContext: DataContext) = rename(project, dataContext)

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {
        if (dataContext != null) rename(project, dataContext)
    }

    private fun rename(project: Project, dataContext: DataContext) {
        GroupSelection.of(dataContext).groups.singleOrNull()?.let { GroupActions.renameGroup(project, it) }
    }
}
