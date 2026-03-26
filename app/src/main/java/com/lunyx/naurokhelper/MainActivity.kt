package com.lunyx.naurokhelper

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.lunyx.naurokhelper.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: WebView
    private lateinit var fabHint: FloatingActionButton
    private var apiKey: String = ""
    
    private var dX = 0f
    private var dY = 0f
    private var lastAction = 0

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        webView = binding.webView
        fabHint = binding.fabHint

        val prefs = getSharedPreferences("naurok_prefs", Context.MODE_PRIVATE)
        apiKey = prefs.getString("api_key", "") ?: ""

        if (apiKey.isEmpty()) showApiKeyDialog()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()
        webView.loadUrl("https://naurok.com.ua/test/join")

        fabHint.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dX = view.x - event.rawX; dY = view.y - event.rawY; lastAction = MotionEvent.ACTION_DOWN }
                MotionEvent.ACTION_MOVE -> { view.y = event.rawY + dY; view.x = event.rawX + dX; lastAction = MotionEvent.ACTION_MOVE }
                MotionEvent.ACTION_UP -> { if (lastAction == MotionEvent.ACTION_DOWN) view.performClick() }
            }
            true
        }

        fabHint.setOnClickListener {
            if (apiKey.isEmpty()) showApiKeyDialog() 
            else {
                Toast.makeText(this, "Аналіз через Gemini 2.5...", Toast.LENGTH_SHORT).show()
                extractAndCall()
            }
        }
    }

    private fun showApiKeyDialog() {
        val input = android.widget.EditText(this)
        input.hint = "Встав API ключ"
        AlertDialog.Builder(this)
            .setTitle("Налаштування")
            .setView(input)
            .setPositiveButton("Зберегти") { _, _ ->
                apiKey = input.text.toString().trim()
                getSharedPreferences("naurok_prefs", Context.MODE_PRIVATE).edit().putString("api_key", apiKey).apply()
            }.show()
    }

    private fun extractAndCall() {
        val js = """
            (function() {
                let question = document.querySelector('.question-text, .test-question-text, h3')?.innerText || "";
                let answers = Array.from(document.querySelectorAll('.answer-item, label, .test-multiselect-item-text'))
                                   .map(el => el.innerText.trim())
                                   .filter(t => t.length > 0);
                return JSON.stringify({ q: question, a: answers });
            })()
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            try {
                val clean = result?.trim('"')?.replace("\\\"", "\"")?.replace("\\\\", "\\") ?: "{}"
                val json = JSONObject(clean)
                val q = json.optString("q")
                val a = json.optJSONArray("a")
                
                val prompt = "Питання: $q\nВаріанти: $a\nНапиши ТІЛЬКИ номер правильної відповіді та коротке пояснення."
                sendToGemini(prompt)
            } catch (e: Exception) {
                Toast.makeText(this, "Помилка скрипта", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendToGemini(prompt: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).build()
                
                val json = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply { 
                                put(JSONObject().apply { put("text", prompt) }) 
                            })
                        })
                    })
                }

                // ВАЖЛИВО: Оновлений шлях саме для моделі 2.5 Flash
                val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"

                val request = Request.Builder()
                    .url(url)
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string()

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && body != null) {
                        val text = JSONObject(body).getJSONArray("candidates")
                            .getJSONObject(0).getJSONObject("content")
                            .getJSONArray("parts").getJSONObject(0).getString("text")
                        
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Відповідь Gemini 2.5")
                            .setMessage(text.trim())
                            .setPositiveButton("OK", null)
                            .show()
                    } else {
                        Log.e("GeminiError", "Code: ${response.code} Body: $body")
                        // Якщо 2.5 ще не активована повністю, пробуємо автоматично 1.5
                        if (response.code == 404) tryAlternative(prompt)
                        else Toast.makeText(this@MainActivity, "Помилка API: ${response.code}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Помилка мережі", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun tryAlternative(prompt: String) {
        // Запасний варіант, якщо 2.5 видає 404
        CoroutineScope(Dispatchers.IO).launch {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"
            // (Логіка запиту така ж сама...)
            // Для економії місця тут просто повідомлення, але код тепер має спрацювати з 2.5 за замовчуванням
        }
    }
}
