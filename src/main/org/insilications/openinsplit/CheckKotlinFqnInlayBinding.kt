package org.insilications.openinsplit

import com.intellij.codeInsight.hints.declarative.InlayActionHandlerBean
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import org.insilications.openinsplit.codeInsight.hints.KotlinFqnDeclarativeInlayActionHandler

/** Checks that our `KotlinFqnDeclarativeInlayActionHandler` inlay action handler is correctly bound in the extension points. */
internal class CheckKotlinFqnInlayBinding : ProjectActivity {
    companion object {
        private val LOG: Logger = Logger.getInstance("org.insilications.openinsplit")
        private const val NOTIFICATION_GROUP_ID = "Open In Split View"
    }

    override suspend fun execute(project: Project) {
        val id = "kotlin.fqn.class"
        val expectedFqn: String = KotlinFqnDeclarativeInlayActionHandler::class.java.name

        @Suppress("UnresolvedPluginConfigReference") val ep: ExtensionPointName<InlayActionHandlerBean> =
            ExtensionPointName.create("com.intellij.codeInsight.inlayActionHandler")
        val bean: InlayActionHandlerBean? = ep.extensionList.firstOrNull { it.handlerId == id }
        val declaredFqn: String = bean?.implementationClass ?: "<none>"

        LOG.info("Inlay handler binding (declared) for '$id' -> $declaredFqn")

        if (declaredFqn != expectedFqn) {
            @Suppress("LongLine") NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP_ID).createNotification(
                "Kotlin FQN handler override not active",
                "Expected: $expectedFqn, but EP declares: $declaredFqn. Verify order=\"first\" and <depends>org.jetbrains.kotlin</depends> in plugin.xml, and check for competing plugins.",
                NotificationType.WARNING,
            ).notify(project)
        }
    }
}