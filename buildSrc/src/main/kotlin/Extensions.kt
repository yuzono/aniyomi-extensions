import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.extra

var ExtensionAware.baseVersionCode: Int
    get() = extra.get("baseVersionCode") as Int
    set(value) = extra.set("baseVersionCode", value)

fun Project.getDependents(): Set<Project> {
    val dependentProjects = mutableSetOf<Project>()

    rootProject.allprojects.forEach { project ->
        project.configurations.forEach { configuration ->
            configuration.dependencies.forEach { dependency ->
                if (dependency is ProjectDependency && dependency.path == path) {
                    dependentProjects.add(project)
                }
            }
        }
    }

    return dependentProjects
}

fun Project.printDependentExtensions() {
    printDependentExtensions(mutableSetOf())
}

private fun Project.printDependentExtensions(visited: MutableSet<String>) {
    if (path in visited) return
    visited.add(path)

    getDependents().forEach { project ->
        when {
            project.path.startsWith(":src:") ->
                println(project.path)
            project.path.startsWith(":lib-multisrc:") ->
                project.getDependents().forEach { println(it.path) }
            project.path.startsWith(":lib:") ->
                project.printDependentExtensions(visited)
        }
    }
}
