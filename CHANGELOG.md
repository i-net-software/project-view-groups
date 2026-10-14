# Changelog

## [Unreleased]

### Added

- Groups for the top-level nodes of the Project tool window (modules, linked Gradle builds, Multi-Project Workspace projects), nestable to any depth.
- Works in the **Project** and **Packages** views, with and without the Multi-Project Workspace plugin.
- **Project Group…** in the Project tool window's **+** menu and in **File | New**.
- Context menu: **Move to Group**, **Remove from Group**, **Rename Group…** (also Shift+F6), **Delete Group** (also Delete), **Manage Project Groups…**.
- Drag and drop of projects onto a group; with the Multi-Project Workspace plugin, onto the workspace node to remove them from their group.
- Groups are stored per user in the workspace file (`.idea/workspace.xml`).

### Fixed

- Documentation: the scope-based views (Project Files, Production, Tests, Changed Files) cannot show groups; they were wrongly listed as supported.
- Packages view: Gradle builds shown as module groups (`CCBase` with `CCBase.main`, `CCBase.test`, …) are grouped as well.
