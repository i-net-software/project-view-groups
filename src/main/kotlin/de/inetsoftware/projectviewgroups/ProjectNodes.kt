package de.inetsoftware.projectviewgroups

import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.impl.ModuleGroup
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory

/** Identifies the "projects" of the Project tool window: the groupable top-level nodes of each view. */
object ProjectNodes {
    private const val WORKSPACE_NODE = "com.intellij.ide.workspace.projectView.WorkspaceNode"

    /**
     * True for the node whose children are the projects: the root of a view, or a node directly below it
     * that stands for the whole project (the Multi-Project Workspace node).
     */
    fun isContainer(node: AbstractTreeNode<*>?): Boolean {
        if (node !is ProjectViewNode<*> || node.value !is Project) return false
        val parent = node.parent ?: return true
        return parent.parent == null && parent.value is Project
    }

    fun isWorkspaceNode(node: AbstractTreeNode<*>): Boolean = node.javaClass.name == WORKSPACE_NODE

    /** A node that can be put into a group: a project below a container or a group. */
    fun isProjectNode(node: AbstractTreeNode<*>): Boolean =
        node !is GroupNode && (node.parent is GroupNode || isContainer(node.parent)) && keyOf(node) != null

    /** The key a project is stored under: its directory path. Must be called with read access. */
    fun keyOf(node: AbstractTreeNode<*>): String? = when (val value = node.value) {
        is PsiDirectory -> if (value.isValid) value.virtualFile.path else null
        is Module -> moduleKey(value)
        is ModuleGroup -> moduleGroupKey(value, node.project ?: return null)
        is GroupId -> null
        else -> (node as? ProjectViewNode<*>)?.virtualFile?.takeIf { it.isValid && it.isDirectory }?.path
    }

    private fun moduleKey(module: Module): String? {
        if (module.isDisposed) return null
        return ModuleRootManager.getInstance(module).contentRoots.firstOrNull()?.path ?: "module:${module.name}"
    }

    /**
     * A module group such as Gradle's `CCBase` (holding `CCBase.main`, `CCBase.test`, …) in the Packages view:
     * the directory of the module with the group's name, or else the common directory of the group's modules.
     */
    private fun moduleGroupKey(group: ModuleGroup, project: Project): String? {
        if (project.isDisposed) return null
        ModuleManager.getInstance(project).findModuleByName(group.qualifiedName)?.let { return moduleKey(it) }
        val roots = group.modulesInGroup(project, true).flatMap { ModuleRootManager.getInstance(it).contentRoots.asList() }
        if (roots.isEmpty()) return null
        return roots.reduce { a, b -> VfsUtilCore.getCommonAncestor(a, b) ?: return null }.path
    }

    /** Display name for a key outside the tree. */
    fun displayName(key: String): String = key.removePrefix("module:").trimEnd('/').substringAfterLast('/')

    /**
     * Candidate projects for the management dialog: module content roots that are not nested inside
     * another content root (the project directory itself does not count, it holds the workspace projects),
     * plus everything already assigned.
     */
    fun candidates(project: Project, model: GroupModel): List<String> {
        val roots = ModuleManager.getInstance(project).modules
            .flatMap { ModuleRootManager.getInstance(it).contentRoots.asList() }
            .distinct()
        val baseDir = project.guessProjectDir()
        val topLevel = roots.filter { root ->
            roots.none { it != root && it != baseDir && VfsUtilCore.isAncestor(it, root, true) }
        }
        val keys = LinkedHashSet<String>()
        topLevel.mapTo(keys, VirtualFile::getPath)
        model.all().forEach { keys.addAll(it.members) }
        return keys.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, ::displayName))
    }
}
