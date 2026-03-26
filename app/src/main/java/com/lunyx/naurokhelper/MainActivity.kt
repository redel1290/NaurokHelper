package com.lunyx.naurokhelper

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
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

        // Завантаження API ключа
        val prefs = getSharedPreferences("naurok_prefs", Context.MODE_PRIVATE)
        apiKey = prefs.getString("api_key", "") ?: ""

        if (apiKey.isEmpty()) {
            showApiKeyDialog()
        }

        // Налаштування WebView
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }

        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(WebAppInterface(this), "Android")

        // Адресний рядок
        addressBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO || 
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                val input = addressBar.text.toString().trim()
                loadUrl(input)
                
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(addressBar.windowToken, 0)
                webView.requestFocus()
                true
            } else false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                addressBar.setText(url)
            }
        }

        webView.loadUrl("https://www.google.com")

        // Draggable FAB (Рухома кнопка)
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
                    if (lastAction == MotionEvent.ACTION_DOWN) {
                        view.performClick()
                    }
                }
            }
            true
        }

        fabHint.setOnClickListener {
            if (apiKey.isEmpty()) {
                showApiKeyDialog()
            } else {
                extractQuestionAndGetHint()
            }
        }
    }

    private fun loadUrl(input: String) {
        val url = when {
            input.startsWith("http") -> input
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> "https://www.google.com/search?q=${android.net.Uri.encode(input)}"
        }
        webView.loadUrl(url)
    }

    private fun showApiKeyDialog() {
        val input = android.widget.EditText(this)
        input.hint = "Вставте Gemini API ключ"
        
        val prefs = getSharedPreferences("naurok_prefs", Context.MODE_PRIVATE)
        input.setText(prefs.getString("api_key", ""))

        AlertDialog.Builder(this)
            .setTitle("Налаштування API")
            .setMessage("Отримайте ключ на: https://aistudio.google.com/apikey")
            .setView(input)
            .setPositiveButton("Зберегти") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isNotEmpty()) {
                    apiKey = key
                    prefs.edit().putString("api_key", key).apply()
                    Toast.makeText(this, "Збережено", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    private fun extractQuestionAndGetHint() {
        // Оптимізований JS скрипт для Naurok
        val js = """
            (function() {
                try {
                    let question = '';
                    let answers = [];
                    
                    const qEl = document.querySelector('.question-text, .test-question, h3, h4');
                    if (qEl) question = qEl.innerText.trim();
                    
                    const labels = document.querySelectorAll('.answer-item, label, .test-multiselect-item');
                    labels.forEach(el => {
                        let txt = el.innerText.trim();
                        if (txt && txt.length > 0 && txt.length < 300) answers.push(txt);
                    });
                    
                    return JSON.stringify({
                        question: question,
                        answers: answers.slice(0, 10) // Обмежуємо кількість відповідей
                    });
                } catch (e) {
                    return JSON.stringify({ error: e.message });
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            try {
                val clean = result.trim('"').replace("\\\"", "\"").replace("\\n", "\n")
                val json = JSONObject(clean)
                
                val question = json.optString("question", "")
                val answersArray = json.optJSONArray("answers")
                
                if (question.isEmpty()) {
                    Toast.makeText(this, "Питання не знайдено", Toast.LENGTH_SHORT).show()
                    return@evaluateJavascript
                }

                val answersList = mutableListOf<String>()
                answersArray?.let {
                    for (i in 0 until it.length()) answersList.add(it.getString(i))
                }
                
                getGeminiHint(question, answersList)
            } catch (e: Exception) {
                Toast.makeText(this, "Помилка скрипта", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getGeminiHint(question: String, answers: List<String>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()

                val prompt = buildString {
                    append("Питання: $question\n\nВаріанти:\n")
                    answers.forEachIndexed { i, a -> append("${i + 1}. $a\n") }
                    append("\nНапиши тільки номер правильної відповіді та коротке пояснення.")
                }

                // Виправлена структура JSON та URL (v1beta)
                val requestBody = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
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
                val resString = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val json = JSONObject(resString)
                    val text = json.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")

                    withContext(Dispatchers.Main) {
                        showHintDialog(question, answers, text)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "API Error: ${response.code}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Помилка: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showHintDialog(question: String, answers: List<String>, hint: String) {
        AlertDialog.Builder(this)
            .setTitle("Підказка")
            .setMessage("❓ $question\n\n🤖 Відповідь:\n$hint")
            .setPositiveButton("Зрозумів", null)
            .show()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    inner class WebAppInterface(private val context: Context) {
        @JavascriptInterface
        fun showToast(message: String) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
