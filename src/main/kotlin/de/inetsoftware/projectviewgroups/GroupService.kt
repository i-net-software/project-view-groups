package de.inetsoftware.projectviewgroups

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/** Holds the groups of a project, stored per user in the workspace file. */
@Service(Service.Level.PROJECT)
@State(name = "ProjectViewGroups", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class GroupService(private val project: Project) : PersistentStateComponent<GroupsState> {

    /** Immutable snapshot; tree structure providers read it from background threads. */
    @Volatile
    var model: GroupModel = GroupModel()
        private set

    override fun getState(): GroupsState = model.toState()

    override fun loadState(state: GroupsState) {
        model = GroupModel.fromState(state)
        refreshProjectView()
    }

    /** Applies [change] to a copy of the model, publishes it and refreshes the Project tool window. */
    fun update(change: (GroupModel) -> Unit) {
        val copy = model.copy()
        change(copy)
        model = copy
        refreshProjectView()
    }

    fun replace(newModel: GroupModel) {
        model = newModel.copy()
        refreshProjectView()
    }

    private fun refreshProjectView() {
        ApplicationManager.getApplication().invokeLater({
            val view = ProjectView.getInstance(project)
            for (id in view.paneIds) view.getProjectViewPaneById(id)?.updateFromRoot(true)
        }, project.disposed)
    }

    companion object {
        fun getInstance(project: Project): GroupService = project.service()
    }
}
