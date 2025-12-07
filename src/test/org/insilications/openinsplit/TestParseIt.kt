package org.insilications.openinsplit

// import com.intellij.testFramework.IndexingTestUtil.Companion.waitUntilIndexesAreReady
import com.intellij.openapi.diagnostic.Logger
import com.intellij.testFramework.IndexingTestUtil.Companion.waitUntilIndexesAreReady
import org.junit.Test
import kotlin.test.assertNotNull

//class TestParseIt : LightJavaCodeInsightFixtureTestCase() {
class TestParseIt : KotlinLightCodeInsightFixtureTestCase() {
    companion object {
        private val LOG: Logger = Logger.getInstance("org.insilications.openinsplit")
    }

    // Enable K2 Mode for tests (if required by your logic)
    override fun setUp() {
        // Set K2 property before setup if your action strictly depends on K2 structures
        System.setProperty("idea.kotlin.plugin.use.k2", "true")
        super.setUp()
    }

    @Test
    fun testGenerate() {
        // 1. Configure a Kotlin file
        myFixture.configureByText(
            "MyFile.kt", """
            fun <caret>main() {
                println("Hello")
            }
        """.trimIndent()
        )

        waitUntilIndexesAreReady(myFixture.project)
        // 2. Perform your action
        // If it's a registered action:

        myFixture.performEditorAction("SymbolsInformationAction")

        LOG.info("ANALYSIS")
        val element = myFixture.elementAtCaret
        println("\nelement.text: ${element.text}")
        assertNotNull(element, "'element' is Null")
    }
}