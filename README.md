# Project View Groups

[![CI](https://github.com/i-net-software/project-view-groups/actions/workflows/ci.yml/badge.svg)](https://github.com/i-net-software/project-view-groups/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

IntelliJ IDEA plugin that groups the projects in the Project tool window, for example by product.

```
▾ ▣ Reporting                3 projects
    ▾ ▣ Server               1 project
        ▸ report-server
    ▸ report-core
    ▸ report-web
▸ ▣ Clear                    2 projects
▸ build-tools
▸ helpdesk
```

## What is a "project"

Every top-level node of a Project tool window view that stands for a directory or module: modules, linked Gradle builds outside the project directory, and the projects of the [Multi-Project Workspace](https://plugins.jetbrains.com/plugin/24569-multi-project-workspace) plugin (below the workspace node). Groups only change the view; no files or modules are moved.

## Features

* Groups nest to any depth.
* All views of the Project tool window: **Project**, **Packages** (with *Show Modules*), **Project Files**, **Production**, **Tests**, …
* Works with and without the Multi-Project Workspace plugin.
* **+ | Project Group…** in the Project tool window (and **File | New | Project Group…**). With a group selected, the new group is nested in it.
* Context menu of projects and groups:
  * **Move to Group** – all groups, plus *New Group…* that takes the selection along
  * **Remove from Group** – back to the top level
  * **Rename Group…** – also Shift+F6
  * **Delete Group** – also Delete; projects and subgroups move up one level
  * **Manage Project Groups…** – dialog with the group tree and a checkbox list of all projects (also in the ⋮ menu of the tool window)
* Drag projects onto a group. With the Multi-Project Workspace plugin, dropping onto the workspace node removes them from their group.
* Groups are stored per user in `.idea/workspace.xml` (component `ProjectViewGroups`), keyed by the project directory.

A view only shows the groups that contain one of its projects, plus groups that are still empty.

## Installation

Download `project-view-groups-<version>.zip` from the [latest release](https://github.com/i-net-software/project-view-groups/releases/latest), then in IntelliJ IDEA 2026.2 or newer: **Settings → Plugins → ⚙ → Install Plugin from Disk…** and select the ZIP.

## Building

Requires JDK 25.

```bash
./gradlew check buildPlugin   # plugin ZIP in build/distributions
./gradlew runIde              # sandbox IDE with the plugin
./gradlew runIdeWithWorkspace # sandbox IDE with the plugin and Multi-Project Workspace
```

## Releasing

Push a tag `v<version>` (e.g. `v1.1.0`, or `v1.1.0-beta.1` for the beta channel). The release workflow builds the plugin with that version and attaches the ZIP to a GitHub release; with the `PUBLISH_TOKEN` secret set it also publishes to the JetBrains Marketplace.

## License

[Apache License 2.0](LICENSE)
