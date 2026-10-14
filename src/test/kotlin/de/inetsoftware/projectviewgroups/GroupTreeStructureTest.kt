package de.inetsoftware.projectviewgroups

import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.nodes.PackageViewProjectNode
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.ide.util.treeView.AbstractTreeStructure
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.projectView.TestProjectTreeStructure
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PsiTestUtil

/** Runs the real Project and Packages view tree structures with the plugin's tree structure provider. */
class GroupTreeStructureTest : HeavyPlatformTestCase() {

    private lateinit var dirs: Map<String, VirtualFile>

    override fun setUp() {
        super.setUp()
        val root = tempDir.createVirtualDir()
        dirs = listOf("report-core", "report-server", "clear-core", "helpdesk").associateWith { name ->
            val dir = runWriteAction {
                root.createChildDirectory(this, name).also { d ->
                    d.createChildDirectory(this, "src").createChildDirectory(this, name.replace("-", ""))
                        .createChildData(this, "A.java").setBinaryContent("package ${name.replace("-", "")}; class A {}".toByteArray())
                }
            }
            val module = createModule(name)
            PsiTestUtil.addContentRoot(module, dir)
            PsiTestUtil.addSourceRoot(module, dir.findChild("src")!!)
            dir
        }
    }

    private fun groups(): GroupService = GroupService.getInstance(project)

    private fun key(name: String) = dirs.getValue(name).path

    fun testWithoutGroupsTheTreeIsUnchanged() {
        val text = dump(TestProjectTreeStructure(project, testRootDisposable))
        assertTrue(text, text.contains("\n report-core\n"))
        assertFalse(text, text.contains("[group]"))
    }

    fun testProjectViewGroupsNested() {
        groups().update { model ->
            model.create("Reporting", id = "rep")
            model.create("Server", parentId = "rep", id = "srv")
            model.create("Empty", id = "empty")
            model.assign(listOf(key("report-core")), "rep")
            model.assign(listOf(key("report-server")), "srv")
        }
        val text = dump(TestProjectTreeStructure(project, testRootDisposable))
        assertTrue(text, text.contains("\n [group] Reporting\n  [group] Server\n   report-server\n  report-core\n"))
        assertTrue(text, text.contains("\n [group] Empty\n"))
        assertTrue(text, text.contains("\n clear-core\n"))
        assertFalse("grouped project must not stay at the top level", Regex("\n report-core\n").containsMatchIn(text))
    }

    fun testGroupWithProjectsMissingInViewIsHidden() {
        groups().update { model ->
            model.create("Gone", id = "gone")
            model.assign(listOf("/does/not/exist"), "gone")
        }
        val text = dump(TestProjectTreeStructure(project, testRootDisposable))
        assertFalse(text, text.contains("Gone"))
    }

    fun testGroupContainsFilesOfItsProjects() {
        groups().update { model ->
            model.create("Reporting", id = "rep")
            model.assign(listOf(key("report-core")), "rep")
        }
        val structure = TestProjectTreeStructure(project, testRootDisposable)
        val group = structure.getChildElements(structure.rootElement).filterIsInstance<GroupNode>().single()
        val file = dirs.getValue("report-core").findFileByRelativePath("src/reportcore/A.java")!!
        assertTrue(group.contains(file))
        assertFalse(group.contains(dirs.getValue("helpdesk")))
    }

    fun testPackagesViewGroupsModules() {
        groups().update { model ->
            model.create("Clear", id = "clr")
            model.assign(listOf(key("clear-core")), "clr")
        }
        val structure = object : TestProjectTreeStructure(project, testRootDisposable) {
            override fun createRoot(project: Project, settings: ViewSettings): AbstractTreeNode<*> = PackageViewProjectNode(project, settings)
            override fun isShowModules() = true
        }
        val text = dump(structure)
        assertTrue(text, text.contains("\n [group] Clear\n  clear-core\n"))
        assertTrue(text, text.contains("\n helpdesk\n"))
    }

    fun testRealPackagesPaneGroupsModules() {
        groups().update { model ->
            model.create("Clear", id = "clr")
            model.assign(listOf(key("clear-core")), "clr")
        }
        val pane = object : com.intellij.ide.projectView.impl.PackageViewPane(project) {
            fun structure() = createStructure()
        }
        com.intellij.openapi.util.Disposer.register(testRootDisposable, pane)
        val text = dump(pane.structure())
        assertTrue(text, text.contains("\n [group] Clear\n  clear-core\n"))
    }

    /** Gradle-like build: root module plus source set modules, shown as one module group in Packages. */
    fun testPackagesViewGroupsGradleModuleGroups() {
        val build = runWriteAction {
            tempDir.createVirtualDir().createChildDirectory(this, "ccbase").also { b ->
                for (sourceSet in listOf("main", "test")) {
                    (b.findChild("src") ?: b.createChildDirectory(this, "src")).let { it.findChild(sourceSet) ?: it.createChildDirectory(this, sourceSet) }
                        .createChildDirectory(this, "java").createChildDirectory(this, "ccbase$sourceSet")
                        .createChildData(this, "A.java").setBinaryContent("package ccbase$sourceSet; class A {}".toByteArray())
                }
            }
        }
        PsiTestUtil.addContentRoot(createModule("ccbase"), build)
        for (sourceSet in listOf("main", "test")) {
            val module = createModule("ccbase.$sourceSet")
            val root = build.findFileByRelativePath("src/$sourceSet")!!
            PsiTestUtil.addContentRoot(module, root)
            PsiTestUtil.addSourceRoot(module, root.findChild("java")!!)
        }
        groups().update { model ->
            model.create("Clear Reports", id = "cr")
            model.assign(listOf(build.path), "cr")
        }
        val pane = object : com.intellij.ide.projectView.impl.PackageViewPane(project) {
            fun structure() = createStructure()
        }
        com.intellij.openapi.util.Disposer.register(testRootDisposable, pane)
        val text = dump(pane.structure())
        assertTrue(text, text.contains("\n [group] Clear Reports\n  ccbase\n"))
        assertFalse(text, Regex("\n ccbase\n").containsMatchIn(text))
    }

    fun testProjectNodesAreRecognized() {
        groups().update { it.create("Reporting", id = "rep") }
        val structure = TestProjectTreeStructure(project, testRootDisposable)
        val top = structure.getChildElements(structure.rootElement).map { it as AbstractTreeNode<*> }
        val core = top.single { (it.value as? com.intellij.psi.PsiDirectory)?.name == "report-core" }
        assertTrue(ProjectNodes.isProjectNode(core))
        assertEquals(key("report-core"), ProjectNodes.keyOf(core))
        assertFalse(ProjectNodes.isProjectNode(top.filterIsInstance<GroupNode>().single()))
    }

    fun testCandidatesAreTopLevelContentRoots() {
        val candidates = ProjectNodes.candidates(project, GroupModel())
        for (name in dirs.keys) assertTrue(candidates.toString(), key(name) in candidates)
    }

    /** Two levels of the tree, one node per line, groups marked with `[group]`. */
    private fun dump(structure: AbstractTreeStructure): String {
        val result = StringBuilder("\n")
        fun visit(element: Any, depth: Int) {
            for (child in structure.getChildElements(element)) {
                val node = child as AbstractTreeNode<*>
                node.update()
                val name = node.presentation.presentableText ?: node.toString()
                result.append(" ".repeat(depth)).append(if (node is GroupNode) "[group] $name" else name).append('\n')
                if (node is GroupNode) visit(node, depth + 1)
            }
        }
        visit(structure.rootElement, 1)
        return result.toString()
    }}
