package org.insilications.openinsplit


//import org.jetbrains.kotlin.idea.test.KotlinLightCodeInsightFixtureTestCase

//import org.jetbrains.kotlin.idea.test.KotlinLightCodeInsightFixtureTestCase

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import org.junit.Test

class TestGeneration : LightJavaCodeInsightFixtureTestCase() {

//    override fun getProjectDescriptor(): LightProjectDescriptor = KotlinWithJdkAndRuntimeLightProjectDescriptor()

    // Enable K2 Mode for tests (if required by your logic)
    override fun setUp() {
        // Set K2 property before setup if your action strictly depends on K2 structures
        System.setProperty("idea.kotlin.plugin.use.k2", "true")
        super.setUp()
    }

//    override fun tearDown() {
//        super.tearDown()
//        System.clearProperty("idea.kotlin.plugin.use.k2")
//    }

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

        // Or if you want to test the logic directly:
        val element = myFixture.elementAtCaret
        println("PORRA: ${element.text}")
        assertNotNull(element)

        // Your custom logic assertion here
    }
}