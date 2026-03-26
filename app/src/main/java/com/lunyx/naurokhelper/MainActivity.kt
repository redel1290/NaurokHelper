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
            else extractAndCall()
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
        // Спеціальний скрипт під структуру, яку ти надав
        val js = """
            (function() {
                try {
                    // Шукаємо текст питання
                    let q = document.querySelector('.test-content-text-inner')?.innerText || "";
                    
                    // Шукаємо посилання на картинку
                    let img = document.querySelector('.test-content-image img')?.src || "";
                    
                    // Збираємо всі варіанти відповідей
                    let options = Array.from(document.querySelectorAll('.question-option-inner-content'))
                                       .map(el => el.innerText.trim());
                    
                    return JSON.stringify({ question: q, imageUrl: img, answers: options });
                } catch (e) { return JSON.stringify({ error: e.message }); }
            })()
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            try {
                val clean = result?.trim('"')?.replace("\\\"", "\"")?.replace("\\\\", "\\") ?: "{}"
                val json = JSONObject(clean)
                
                val q = json.optString("question")
                val img = json.optString("imageUrl")
                val a = json.optJSONArray("answers")
                
                val prompt = "Питання: $q\nВаріанти: $a\n" + 
                             (if (img.isNotEmpty()) "Також подивись на картинку за цим посиланням: $img\n" else "") +
                             "Напиши номер правильної відповіді та коротко чому."
                
                sendToGemini(prompt)
            } catch (e: Exception) {
                Toast.makeText(this, "Помилка аналізу сторінки", Toast.LENGTH_SHORT).show()
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

                // Пробуємо Gemini 2.5 Flash, якщо ні - 1.5
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
                            .setTitle("Відповідь")
                            .setMessage(text.trim())
                            .setPositiveButton("OK", null)
                            .show()
                    } else {
                        Toast.makeText(this@MainActivity, "Помилка API: ${response.code}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Мережева помилка", Toast.LENGTH_SHORT).show() }
            }
        }
    }
}
