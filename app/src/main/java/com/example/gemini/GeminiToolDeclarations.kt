package com.example.gemini

import org.json.JSONArray
import org.json.JSONObject

object GeminiToolDeclarations {

    val SYSTEM_PROMPT = """
You are Arushi, a warm, highly intelligent, quick, and conversational AI voice companion for Android.

Key characteristics & rules:
1. MULTILINGUAL AUTO-DETECTION: You speak fluently and naturally in all languages supported by Gemini Live, including English, Hindi (हिन्दी), Hinglish (conversational Indian English mixed with Hindi), Marathi, Gujarati, Bengali, Tamil, Telugu, Kannada, Malayalam, Punjabi, and Urdu.
   - Automatically detect the language being spoken.
   - If user speaks Hindi, reply in natural Hindi.
   - If user speaks English, reply in friendly English.
   - If user speaks Hinglish, reply naturally in Hinglish (e.g. "Haan bilkul, main abhi WhatsApp open kar rahi hoon!").
   - If user switches language mid-conversation, smoothly switch along with them.
   - Never force manual language selection.

2. REAL APP CONTROL & FUNCTION CALLING:
   - When the user asks to open WhatsApp, call `openWhatsApp()`.
   - When the user asks to open an app (e.g. YouTube, Instagram, Chrome, Settings, Camera, Maps), call `openApp(appName)`.
   - When the user asks to open a link, call `openUrl(url)`.
   - When the user gives a phone number to call (e.g. "Call 9876543210"), call `makeCall(phoneNumber)`.
   - When the user asks to call a person by name or relation (e.g. "Call Mom", "Call Mummy", "Call Rahul", "Mummy ko call karo", "Rahul ko call karo"), call `callContact(contactName)`.
   - NEVER simply claim you opened an app or made a call without calling the tool! The tool actually executes the action on the Android device.
   - When you receive the tool response:
     - If successful, confirm briefly and warmly in voice (e.g. "Opening WhatsApp for you!", "Calling Mummy now!").
     - If multiple contacts found, ask for clarification (e.g., "I found Rahul Sharma and Rahul Verma. Which one should I call?").
     - If contact not found or app not installed, explain gracefully.
3. CONVERSATION STYLE: Keep spoken voice responses concise, pleasant, and natural. Avoid lengthy robotic bullet points.
""".trimIndent()

    fun getToolsJsonArray(): JSONArray {
        val functionDeclarations = JSONArray()

        // 1. openWhatsApp
        val openWhatsApp = JSONObject().apply {
            put("name", "openWhatsApp")
            put("description", "Opens WhatsApp application or initiates a WhatsApp chat on the Android device.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                val props = JSONObject().apply {
                    put("phoneNumber", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Optional recipient phone number with country code")
                    })
                    put("message", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Optional pre-filled message text")
                    })
                }
                put("properties", props)
            })
        }
        functionDeclarations.put(openWhatsApp)

        // 2. openApp
        val openApp = JSONObject().apply {
            put("name", "openApp")
            put("description", "Opens an installed Android app such as YouTube, Instagram, Chrome, Settings, Camera, Maps, Spotify, Gmail, Calculator.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                val props = JSONObject().apply {
                    put("appName", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "The name of the app to launch (e.g., 'youtube', 'instagram', 'chrome', 'settings', 'camera', 'maps')")
                    })
                }
                put("properties", props)
                put("required", JSONArray().apply { put("appName") })
            })
        }
        functionDeclarations.put(openApp)

        // 3. openUrl
        val openUrl = JSONObject().apply {
            put("name", "openUrl")
            put("description", "Opens a web link or URL in the Android browser.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                val props = JSONObject().apply {
                    put("url", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "The URL to open, starting with https://")
                    })
                }
                put("properties", props)
                put("required", JSONArray().apply { put("url") })
            })
        }
        functionDeclarations.put(openUrl)

        // 4. makeCall
        val makeCall = JSONObject().apply {
            put("name", "makeCall")
            put("description", "Makes a phone call or opens the dialer for the given phone number.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                val props = JSONObject().apply {
                    put("phoneNumber", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "The telephone number to call")
                    })
                }
                put("properties", props)
                put("required", JSONArray().apply { put("phoneNumber") })
            })
        }
        functionDeclarations.put(makeCall)

        // 5. callContact
        val callContact = JSONObject().apply {
            put("name", "callContact")
            put("description", "Looks up a contact in the phone directory by name or relationship (e.g. 'Mom', 'Mummy', 'Rahul', 'Dad', 'Priya') and initiates a call.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                val props = JSONObject().apply {
                    put("contactName", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "The name or relationship of the contact to call")
                    })
                }
                put("properties", props)
                put("required", JSONArray().apply { put("contactName") })
            })
        }
        functionDeclarations.put(callContact)

        val tools = JSONArray()
        val toolsWrapper = JSONObject().apply {
            put("functionDeclarations", functionDeclarations)
        }
        tools.put(toolsWrapper)
        return tools
    }
}
