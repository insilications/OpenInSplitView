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
internal class CheckKotlinFqnInlayBinding : ProjectActivity {
    companion object {
        private val LOG: Logger = Logger.getInstance("org.insilications.openinsplit")
        private const val NOTIFICATION_GROUP_ID = "Open In Split View"
        private val EXPECTED_IMPLEMENTATION_CLASS: String = KotlinFqnDeclarativeInlayActionHandlerSplit::class.java.name
    }

    override suspend fun execute(project: Project) {
        val id = "kotlin.fqn.class"
//        val expectedFqn: String = KotlinFqnDeclarativeInlayActionHandlerSplit::class.java.name

        val ep: ExtensionPointName<InlayActionHandlerBean> = ExtensionPointName.create("com.intellij.codeInsight.inlayActionHandler")
        val bean: InlayActionHandlerBean? = ep.extensionList.firstOrNull { it.handlerId == id }
        val declaredFqn: String = bean?.implementationClass ?: "<none>"

        LOG.info("Inlay handler binding (declared) for '$id' -> $declaredFqn")

//        if (declaredFqn != expectedFqn) {
        if (declaredFqn != EXPECTED_IMPLEMENTATION_CLASS) {
            @Suppress("LongLine") NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP_ID).createNotification(
                "Kotlin FQN handler override not active",
                "Expected: $EXPECTED_IMPLEMENTATION_CLASS, but EP declares: $declaredFqn. Verify order=\"first\" and <depends>org.jetbrains.kotlin</depends> in plugin.xml, and check for competing plugins.",
                NotificationType.WARNING,
            ).notify(project)
//            @Suppress("LongLine") NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP_ID).createNotification(
//                "Kotlin FQN handler override not active",
//                "Expected: $expectedFqn, but EP declares: $declaredFqn. Verify order=\"first\" and <depends>org.jetbrains.kotlin</depends> in plugin.xml, and check for competing plugins.",
//                NotificationType.WARNING,
//            ).notify(project)
        }
    }
}