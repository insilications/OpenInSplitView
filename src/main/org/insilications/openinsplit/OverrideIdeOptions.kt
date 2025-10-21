package org.insilications.openinsplit

import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

internal class OverrideIdeOptions : ProjectActivity {
    companion object {
        private const val EDITOR_OPEN_INACTIVE_SPLITTER: String = "editor.open.inactive.splitter"
    }

    override suspend fun execute(project: Project) {
        AdvancedSettings.setBoolean(EDITOR_OPEN_INACTIVE_SPLITTER, false)
    }
}
