package org.insilications.openinsplit

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import org.junit.Test
import kotlin.test.assertNotNull

class TestParseIt : LightJavaCodeInsightFixtureTestCase() {
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


        // 2. Perform your action
        // If it's a registered action:
        // myFixture.performEditorAction("MyActionID")

        val element = myFixture.elementAtCaret
        println("element: ${element.text}")
        assertNotNull(element, "asd")
    }
}