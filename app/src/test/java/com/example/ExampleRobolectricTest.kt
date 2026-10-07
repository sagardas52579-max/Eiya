package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.bridge.AndroidActionBridge
import com.example.bridge.BridgeResult
import com.example.gemini.GeminiToolDeclarations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read app_name string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Arushi AI", appName)
  }

  @Test
  fun `verify gemini tools schema has required functions`() {
    val toolsJson = GeminiToolDeclarations.getToolsJsonArray()
    assertNotNull(toolsJson)
    val declarations = toolsJson.getJSONObject(0).getJSONArray("functionDeclarations")
    val functionNames = mutableListOf<String>()
    for (i in 0 until declarations.length()) {
      functionNames.add(declarations.getJSONObject(i).getString("name"))
    }
    assertTrue("Should contain openWhatsApp", functionNames.contains("openWhatsApp"))
    assertTrue("Should contain openApp", functionNames.contains("openApp"))
    assertTrue("Should contain openUrl", functionNames.contains("openUrl"))
    assertTrue("Should contain makeCall", functionNames.contains("makeCall"))
    assertTrue("Should contain callContact", functionNames.contains("callContact"))
  }

  @Test
  fun `test call contact Mummy finds single match`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val bridge = AndroidActionBridge(context)
    bridge.seedContactsIntoDevice()

    val result = bridge.callContact("Mummy")
    assertTrue(result is BridgeResult.Success)
    val success = result as BridgeResult.Success
    assertTrue(success.summary.contains("Mummy"))
  }

  @Test
  fun `test call contact Rahul finds multiple matches for clarification`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val bridge = AndroidActionBridge(context)
    bridge.seedContactsIntoDevice()

    val result = bridge.callContact("Rahul")
    assertTrue(result is BridgeResult.MultipleContacts)
    val multiple = result as BridgeResult.MultipleContacts
    assertEquals(2, multiple.contacts.size)
    assertTrue(multiple.question.contains("Rahul Sharma"))
    assertTrue(multiple.question.contains("Rahul Verma"))
  }

  @Test
  fun `test make call with number`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val bridge = AndroidActionBridge(context)

    val result = bridge.makeCall("9876543210")
    assertTrue(result is BridgeResult.Success)
  }

  @Test
  fun `test open url`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val bridge = AndroidActionBridge(context)

    val result = bridge.openUrl("https://youtube.com")
    assertTrue(result is BridgeResult.Success)
  }
}

