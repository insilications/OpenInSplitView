package org.insilications.openinsplit

import com.intellij.codeInsight.hints.declarative.InlayActionHandlerBean
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import org.insilications.openinsplit.codeInsight.hints.KotlinFqnDeclarativeInlayActionHandlerSplit

/** Checks that our `KotlinFqnDeclarativeInlayActionHandler` inlay action handler is correctly bound in the extension points. */
@Suppress("CompanionObjectInExtension")
internal class CheckKotlinFqnInlayBinding : ProjectActivity {
    companion object {
        private val LOG: Logger = Logger.getInstance("org.insilications.openinsplit")

        @Suppress("UnresolvedPluginConfigReference")
        private val EP = ExtensionPointName<InlayActionHandlerBean>("com.intellij.codeInsight.inlayActionHandler")
        private const val NOTIFICATION_GROUP_ID = "Open In Split View"
        private const val HANDLER_ID = "kotlin.fqn.class"
        private val EXPECTED_IMPLEMENTATION_CLASS: String = KotlinFqnDeclarativeInlayActionHandlerSplit::class.java.name
    }

    override suspend fun execute(project: Project) {
        val bean: InlayActionHandlerBean? = EP.extensionList.firstOrNull { it.handlerId == HANDLER_ID }
        val declaredFqn: String = bean?.implementationClass ?: "<none>"

        LOG.info("Inlay handler binding (declared) for '$HANDLER_ID' -> $declaredFqn")

        if (declaredFqn != EXPECTED_IMPLEMENTATION_CLASS) {
            @Suppress("LongLine") NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP_ID).createNotification(
                "Kotlin FQN handler override not active",
                "Expected: $EXPECTED_IMPLEMENTATION_CLASS, but EP declares: $declaredFqn. Verify order=\"first\" and <depends>org.jetbrains.kotlin</depends> in plugin.xml, and check for competing plugins.",
                NotificationType.WARNING,
            ).notify(project)
        }
    }
}