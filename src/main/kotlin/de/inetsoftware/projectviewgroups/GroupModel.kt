package de.inetsoftware.projectviewgroups

import java.util.UUID

/** A project group. [members] are the keys (directory paths) of the projects assigned directly to it. */
class Group(val id: String, var name: String, var parentId: String?, val members: MutableList<String> = mutableListOf())

/**
 * The group tree of an IDE project, independent of the IDE.
 * Every project key belongs to at most one group; projects without a group stay at the top level.
 * Not thread-safe: [GroupService] mutates a [copy] and publishes it as a whole.
 */
class GroupModel {
    private val groups = LinkedHashMap<String, Group>()

    val isEmpty: Boolean get() = groups.isEmpty()

    fun all(): List<Group> = groups.values.toList()

    operator fun get(id: String): Group? = groups[id]

    /** Direct subgroups of [parentId] (top-level groups for `null`), sorted by name. */
    fun children(parentId: String?): List<Group> =
        groups.values.filter { it.parentId == parentId }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    fun create(name: String, parentId: String? = null, id: String = UUID.randomUUID().toString()): Group {
        require(name.isNotBlank()) { "Group name must not be blank" }
        require(parentId == null || parentId in groups) { "Unknown parent group $parentId" }
        require(id !in groups) { "Duplicate group id $id" }
        return Group(id, name.trim(), parentId).also { groups[id] = it }
    }

    fun rename(id: String, name: String) {
        require(name.isNotBlank()) { "Group name must not be blank" }
        require(id).name = name.trim()
    }

    /** Removes the group; its subgroups and projects move up to its parent. */
    fun delete(id: String) {
        val group = groups.remove(id) ?: return
        groups.values.filter { it.parentId == id }.forEach { it.parentId = group.parentId }
        group.parentId?.let { groups[it] }?.members?.addAll(group.members)
    }

    fun groupOf(key: String): String? = groups.values.firstOrNull { key in it.members }?.id

    /** Moves the projects into [groupId], or to the top level for `null`. */
    fun assign(keys: Collection<String>, groupId: String?) {
        val target = groupId?.let { require(it) }
        groups.values.forEach { it.members.removeAll(keys.toSet()) }
        target?.members?.addAll(keys.distinct())
    }

    /** Moves a group below [newParentId], or to the top level for `null`. */
    fun move(id: String, newParentId: String?) {
        val group = require(id)
        if (newParentId != null) {
            require(newParentId)
            require(!isSelfOrAncestor(id, newParentId)) { "Cannot move a group into itself" }
        }
        group.parentId = newParentId
    }

    /** True if [ancestorId] is [id] or one of its parents. */
    fun isSelfOrAncestor(ancestorId: String, id: String): Boolean {
        var current: String? = id
        val seen = HashSet<String>()
        while (current != null && seen.add(current)) {
            if (current == ancestorId) return true
            current = groups[current]?.parentId
        }
        return false
    }

    /** Group names from the top level down to [id], e.g. `[Product, Server]`. */
    fun path(id: String): List<String> {
        val names = ArrayList<String>()
        var current = groups[id]
        val seen = HashSet<String>()
        while (current != null && seen.add(current.id)) {
            names.add(current.name)
            current = current.parentId?.let { groups[it] }
        }
        return names.asReversed()
    }

    /** All groups depth-first in display order, paired with their depth. */
    fun flatten(): List<Pair<Group, Int>> {
        val result = ArrayList<Pair<Group, Int>>()
        fun visit(parentId: String?, depth: Int) {
            for (child in children(parentId)) {
                result.add(child to depth)
                visit(child.id, depth + 1)
            }
        }
        visit(null, 0)
        return result
    }

    /** True if the group or one of its subgroups has projects assigned. */
    fun hasMembersDeep(id: String): Boolean =
        groups[id]?.members?.isNotEmpty() == true || groups.values.any { it.parentId == id && hasMembersDeep(it.id) }

    fun copy(): GroupModel = fromState(toState())

    fun toState(): GroupsState = GroupsState().also { state ->
        state.groups = groups.values.mapTo(ArrayList()) { group ->
            GroupBean().apply {
                id = group.id
                name = group.name
                parent = group.parentId
                projects = ArrayList(group.members)
            }
        }
    }

    private fun require(id: String): Group = groups[id] ?: throw IllegalArgumentException("Unknown group $id")

    companion object {
        /** Restores a model, dropping dangling parents, parent cycles and duplicate assignments. */
        fun fromState(state: GroupsState): GroupModel {
            val model = GroupModel()
            for (bean in state.groups) {
                if (bean.id.isBlank() || bean.id in model.groups) continue
                model.groups[bean.id] = Group(bean.id, bean.name.ifBlank { "Group" }, bean.parent)
            }
            for (group in model.groups.values) {
                val parent = group.parentId
                if (parent != null && (parent !in model.groups || model.isSelfOrAncestor(group.id, parent))) group.parentId = null
            }
            val assigned = HashSet<String>()
            for (bean in state.groups) {
                val group = model.groups[bean.id] ?: continue
                bean.projects.filter { it.isNotBlank() && assigned.add(it) }.forEach { group.members.add(it) }
            }
            return model
        }
    }
}

/** Persisted form of [GroupModel] (XML-serialized into the workspace file). */
class GroupsState {
    var groups: MutableList<GroupBean> = ArrayList()
}

class GroupBean {
    var id: String = ""
    var name: String = ""
    var parent: String? = null
    var projects: MutableList<String> = ArrayList()
}
