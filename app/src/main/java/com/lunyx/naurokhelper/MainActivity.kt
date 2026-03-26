package com.lunyx.naurokhelper

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
    private lateinit var addressBar: android.widget.EditText
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
        addressBar = binding.addressBar

        val prefs = getSharedPreferences("naurok_prefs", Context.MODE_PRIVATE)
        apiKey = prefs.getString("api_key", "") ?: ""

        if (apiKey.isEmpty()) showApiKeyDialog()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                addressBar.setText(url)
            }
        }

        webView.addJavascriptInterface(WebAppInterface(this), "Android")
        webView.loadUrl("https://naurok.com.ua/test/join")

        addressBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH) {
                val input = addressBar.text.toString().trim()
                val url = if (input.startsWith("http")) input else "https://www.google.com/search?q=$input"
                webView.loadUrl(url)
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(addressBar.windowToken, 0)
                true
            } else false
        }

        fabHint.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dX = view.x - event.rawX
                    dY = view.y - event.rawY
                    lastAction = MotionEvent.ACTION_DOWN
                }
                MotionEvent.ACTION_MOVE -> {
                    view.y = event.rawY + dY
                    view.x = event.rawX + dX
                    lastAction = MotionEvent.ACTION_MOVE
                }
                MotionEvent.ACTION_UP -> {
                    if (lastAction == MotionEvent.ACTION_DOWN) view.performClick()
                }
            }
            true
        }

        fabHint.setOnClickListener {
            if (apiKey.isEmpty()) showApiKeyDialog() 
            else {
                Toast.makeText(this, "Аналізую сторінку...", Toast.LENGTH_SHORT).show()
                extractQuestionAndGetHint()
            }
        }
    }

    private fun showApiKeyDialog() {
        val input = android.widget.EditText(this)
        val prefs = getSharedPreferences("naurok_prefs", Context.MODE_PRIVATE)
        input.setText(prefs.getString("api_key", ""))
        AlertDialog.Builder(this)
            .setTitle("API Ключ")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Зберегти") { _, _ ->
                apiKey = input.text.toString().trim()
                prefs.edit().putString("api_key", apiKey).apply()
            }.show()
    }

    private fun extractQuestionAndGetHint() {
        val js = """
            (function() {
                try {
                    let q = document.querySelector('.question-text, .test-question-text, h3')?.innerText || "";
                    let opts = Array.from(document.querySelectorAll('.answer-item, label, .test-multiselect-item-text'))
                                    .map(el => el.innerText.trim())
                                    .filter(t => t.length > 0);
                    if (!q) q = document.body.innerText.substring(0, 1000);
                    return JSON.stringify({ question: q, answers: opts });
                } catch (e) {
                    return JSON.stringify({ error: e.message });
                }
            })()
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            try {
                val clean = result.trim('"').replace("\\\"", "\"").replace("\\\\", "\\")
                val json = JSONObject(clean)
                getGeminiHint(json.optString("question"), json.optJSONArray("answers"))
            } catch (e: Exception) {
                Toast.makeText(this, "Помилка скрипта", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getGeminiHint(question: String, answersJson: JSONArray?) {
        val answers = mutableListOf<String>()
        answersJson?.let { for (i in 0 until it.length()) answers.add(it.getString(i)) }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()

                val prompt = "Питання: $question\nВаріанти: ${answers.joinToString(", ")}\nДай ТІЛЬКИ правильну відповідь."

                val requestBody = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", prompt) })
                            })
                        })
                    })
                }

                // Використовуємо стабільний шлях v1
                val url = "https://generativelanguage.googleapis.com/v1/models/gemini-1.5-flash:generateContent?key=$apiKey"

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val resBody = response.body?.string()

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && resBody != null) {
                        val text = JSONObject(resBody).getJSONArray("candidates")
                            .getJSONObject(0).getJSONObject("content")
                            .getJSONArray("parts").getJSONObject(0).getString("text")
                        
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Підказка")
                            .setMessage(text.trim())
                            .setPositiveButton("OK", null)
                            .show()
                    } else {
                        // Якщо v1 видав 404, спробуємо v1beta (для деяких ключів це критично)
                        if (response.code == 404) {
                            retryWithBeta(question, answers)
                        } else {
                            Toast.makeText(this@MainActivity, "API помилка: ${response.code}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Помилка мережі", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun retryWithBeta(question: String, answers: List<String>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).build()
                val prompt = "Питання: $question\nВаріанти: ${answers.joinToString(", ")}\nВідповідь:"
                
                val requestBody = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user") // v1beta часто вимагає роль
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", prompt) })
                            })
                        })
                    })
                }

                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val resBody = response.body?.string()

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && resBody != null) {
                        val text = JSONObject(resBody).getJSONArray("candidates")
                            .getJSONObject(0).getJSONObject("content")
                            .getJSONArray("parts").getJSONObject(0).getString("text")
                        
                        AlertDialog.Builder(this@MainActivity).setMessage(text.trim()).setPositiveButton("OK", null).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Навіть Beta повернула 404. Перевірте ключ.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) { /* ignore */ }
        }
    }

    inner class WebAppInterface(private val context: Context) {
        @JavascriptInterface fun showToast(m: String) = Toast.makeText(context, m, Toast.LENGTH_SHORT).show()
    }
}
