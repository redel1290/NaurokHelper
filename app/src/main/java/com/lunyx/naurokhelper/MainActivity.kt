package com.lunyx.naurokhelper

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
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
            // Дозволяємо змішаний контент для кращого завантаження скриптів
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(WebAppInterface(this), "Android")

        addressBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO || 
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                loadUrl(addressBar.text.toString().trim())
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
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
            .setTitle("API Налаштування")
            .setView(input)
            .setPositiveButton("Зберегти") { _, _ ->
                apiKey = input.text.toString().trim()
                prefs.edit().putString("api_key", apiKey).apply()
            }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    private fun extractQuestionAndGetHint() {
        // УЛЬТРА-Скрипт: шукає текст питання скрізь
        val js = """
            (function() {
                try {
                    function findQuestion() {
                        // Пріоритетні класи Naurok
                        let q = document.querySelector('.question-text, .test-question-text, .question-content, h3, h2');
                        if (q && q.innerText.trim().length > 2) return q.innerText.trim();
                        
                        // Якщо не знайшли - шукаємо перший великий блок тексту в області тесту
                        let allDivs = document.querySelectorAll('div');
                        for (let div of allDivs) {
                            if (div.innerText.trim().length > 10 && div.children.length === 0) {
                                return div.innerText.trim();
                            }
                        }
                        return '';
                    }

                    function findAnswers() {
                        let list = [];
                        // Всі можливі елементи відповідей
                        let nodes = document.querySelectorAll('.answer-item, .test-multiselect-item, label, .answer-text, [class*="option"]');
                        nodes.forEach(n => {
                            let t = n.innerText.trim();
                            if (t && t.length > 0 && !list.includes(t)) list.push(t);
                        });
                        return list;
                    }

                    return JSON.stringify({
                        question: findQuestion(),
                        answers: findAnswers()
                    });
                } catch (e) {
                    return JSON.stringify({ error: e.message });
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            try {
                val clean = result.trim('"')
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n", " ")
                
                val json = JSONObject(clean)
                val question = json.optString("question", "")
                val answersArray = json.optJSONArray("answers")
                
                if (question.isBlank() || question == "null") {
                    Toast.makeText(this, "Питання не знайдено. Спробуйте оновити сторінку або прокрутити до питання.", Toast.LENGTH_LONG).show()
                    return@evaluateJavascript
                }

                val answersList = mutableListOf<String>()
                answersArray?.let {
                    for (i in 0 until it.length()) {
                        val text = it.getString(i)
                        if (text.length > 1) answersList.add(text)
                    }
                }
                
                getGeminiHint(question, answersList)
            } catch (e: Exception) {
                Toast.makeText(this, "Помилка парсингу сторінки", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getGeminiHint(question: String, answers: List<String>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .build()

                val prompt = "Питання: $question\nВаріанти: ${answers.joinToString(", ")}\nНапиши тільки номер правильної відповіді (або декілька) і коротке пояснення."

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
                val resBody = response.body?.string()

                if (response.isSuccessful && resBody != null) {
                    val text = JSONObject(resBody).getJSONArray("candidates")
                        .getJSONObject(0).getJSONObject("content")
                        .getJSONArray("parts").getJSONObject(0).getString("text")

                    withContext(Dispatchers.Main) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Підказка")
                            .setMessage(text)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Помилка API: ${response.code}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Помилка мережі", Toast.LENGTH_SHORT).show()
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
