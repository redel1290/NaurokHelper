package com.lunyx.naurokhelper

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
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
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(WebAppInterface(this), "Android")

        addressBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH) {
                loadUrl(addressBar.text.toString().trim())
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(addressBar.windowToken, 0)
                webView.requestFocus()
                true
            } else false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                addressBar.setText(url)
            }
        }

        // Відразу відкриваємо сторінку входу в тест
        webView.loadUrl("https://naurok.com.ua/test/join")

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
            if (apiKey.isEmpty()) showApiKeyDialog() else extractQuestionAndGetHint()
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
            .setTitle("Налаштування Gemini")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Зберегти") { _, _ ->
                apiKey = input.text.toString().trim()
                prefs.edit().putString("api_key", apiKey).apply()
            }
            .show()
    }

    private fun extractQuestionAndGetHint() {
        // Скрипт, який збирає весь видимий текст зі сторінки, якщо не знаходить конкретні класи
        val js = """
            (function() {
                try {
                    let question = "";
                    let answers = [];

                    // 1. Спроба знайти за відомими класами
                    let qEl = document.querySelector('.question-text, .test-question-text, h3');
                    if (qEl) question = qEl.innerText.trim();

                    // 2. Якщо порожньо, беремо найбільший за площею блок тексту у верхній частині
                    if (!question) {
                        let blocks = Array.from(document.querySelectorAll('div, p, span'))
                            .filter(el => el.innerText.trim().length > 15 && el.children.length <= 2);
                        if (blocks.length > 0) question = blocks[0].innerText.trim();
                    }

                    // 3. Збір відповідей (шукаємо елементи, що повторюються)
                    let options = document.querySelectorAll('.answer-item, label, [class*="option"], .test-multiselect-item');
                    options.forEach(opt => {
                        let txt = opt.innerText.trim();
                        if (txt && !answers.includes(txt) && txt.length < 500) {
                            answers.push(txt);
                        }
                    });

                    return JSON.stringify({
                        question: question,
                        answers: answers
                    });
                } catch (e) {
                    return JSON.stringify({ error: e.message });
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            try {
                val clean = result.trim('"').replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", " ")
                val json = JSONObject(clean)
                
                val question = json.optString("question", "")
                val answers = json.optJSONArray("answers")
                
                if (question.isBlank() || (answers != null && answers.length() == 0)) {
                    // Якщо стандартний пошук не спрацював, беремо ВЕСЬ текст сторінки як запасний варіант
                    webView.evaluateJavascript("document.body.innerText") { bodyText ->
                        getGeminiHint("Контекст сторінки: " + bodyText.take(1000), emptyList())
                    }
                } else {
                    val list = mutableListOf<String>()
                    answers?.let { for (i in 0 until it.length()) list.add(it.getString(i)) }
                    getGeminiHint(question, list)
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Помилка зчитування", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getGeminiHint(question: String, answers: List<String>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .build()

                val prompt = if (answers.isEmpty()) {
                    "Знайди питання на цій сторінці та дай відповідь: $question"
                } else {
                    "Питання: $question\nВаріанти: ${answers.joinToString(" | ")}\nНапиши ТІЛЬКИ правильну відповідь."
                }

                val requestBody = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply { put(JSONObject().apply { put("text", prompt) }) })
                        })
                    })
                }

                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val resBody = response.body?.string()

                if (response.isSuccessful && resBody != null) {
                    val text = JSONObject(resBody).getJSONArray("candidates")
                        .getJSONObject(0).getJSONObject("content")
                        .getJSONArray("parts").getJSONObject(0).getString("text")

                    withContext(Dispatchers.Main) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Підказка")
                            .setMessage(text.trim())
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Помилка мережі або API", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    inner class WebAppInterface(private val context: Context) {
        @JavascriptInterface fun showToast(m: String) = Toast.makeText(context, m, Toast.LENGTH_SHORT).show()
    }
}
