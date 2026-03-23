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
import android.widget.FrameLayout
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

        // Перевірка API ключа
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

        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(WebAppInterface(this), "Android")

        // Завантажуємо Naurok
        webView.loadUrl("https://naurok.com.ua")

        // Draggable FAB
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

    private fun showApiKeyDialog() {
        val input = android.widget.EditText(this)
        input.hint = "Вставте Gemini API ключ"
        
        val prefs = getSharedPreferences("naurok_prefs", Context.MODE_PRIVATE)
        input.setText(prefs.getString("api_key", ""))

        AlertDialog.Builder(this)
            .setTitle("Налаштування API")
            .setMessage("Отримайте безкоштовний ключ:\nhttps://aistudio.google.com/apikey")
            .setView(input)
            .setPositiveButton("Зберегти") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isNotEmpty()) {
                    apiKey = key
                    prefs.edit().putString("api_key", key).apply()
                    Toast.makeText(this, "API ключ збережено", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Скасувати", null)
            .setCancelable(false)
            .show()
    }

    private fun extractQuestionAndGetHint() {
        val js = """
            (function() {
                try {
                    // Шукаємо питання
                    let question = '';
                    let answers = [];
                    
                    // Варіант 1: пошук по класах Naurok
                    const questionEl = document.querySelector('.question-text, .test-question, [class*="question"]');
                    if (questionEl) {
                        question = questionEl.innerText.trim();
                    }
                    
                    // Варіант 2: пошук по h3/h4
                    if (!question) {
                        const headings = document.querySelectorAll('h3, h4, h5');
                        for (let h of headings) {
                            const text = h.innerText.trim();
                            if (text.length > 10 && !text.includes('Тест')) {
                                question = text;
                                break;
                            }
                        }
                    }
                    
                    // Шукаємо варіанти відповідей
                    const answerEls = document.querySelectorAll('[class*="answer"], [class*="option"], input[type="radio"] + label, input[type="checkbox"] + label');
                    answerEls.forEach(el => {
                        const text = el.innerText.trim();
                        if (text && text.length > 0 && text.length < 200) {
                            answers.push(text);
                        }
                    });
                    
                    // Якщо не знайшли - шукаємо всі label
                    if (answers.length === 0) {
                        const labels = document.querySelectorAll('label');
                        labels.forEach(label => {
                            const text = label.innerText.trim();
                            if (text && text.length > 1 && text.length < 200 && !text.includes('Запам')) {
                                answers.push(text);
                            }
                        });
                    }
                    
                    if (question && answers.length > 0) {
                        return JSON.stringify({
                            question: question,
                            answers: answers
                        });
                    }
                    
                    return JSON.stringify({
                        error: 'Не вдалося знайти питання. Переконайтесь що ви на сторінці з тестом.'
                    });
                    
                } catch (e) {
                    return JSON.stringify({
                        error: 'Помилка: ' + e.message
                    });
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            try {
                val cleanResult = result.trim('"').replace("\\\"", "\"").replace("\\n", "\n")
                val json = JSONObject(cleanResult)
                
                if (json.has("error")) {
                    Toast.makeText(this, json.getString("error"), Toast.LENGTH_LONG).show()
                } else {
                    val question = json.getString("question")
                    val answersArray = json.getJSONArray("answers")
                    val answers = mutableListOf<String>()
                    for (i in 0 until answersArray.length()) {
                        answers.add(answersArray.getString(i))
                    }
                    
                    getGeminiHint(question, answers)
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Помилка парсингу: ${e.message}", Toast.LENGTH_LONG).show()
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
                    append("Питання: $question\n\n")
                    append("Варіанти відповідей:\n")
                    answers.forEachIndexed { index, answer ->
                        append("${index + 1}. $answer\n")
                    }
                    append("\nВідповідь дай ТІЛЬКИ номером правильного варіанту (1, 2, 3 або 4) і коротке пояснення в одному реченні.")
                }

                val requestBody = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", prompt)
                                })
                            })
                        })
                    })
                }

                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                if (response.isSuccessful && responseBody != null) {
                    val jsonResponse = JSONObject(responseBody)
                    val text = jsonResponse
                        .getJSONArray("candidates")
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
                        Toast.makeText(
                            this@MainActivity,
                            "Помилка API: ${response.code}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Помилка: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showHintDialog(question: String, answers: List<String>, hint: String) {
        val message = buildString {
            append("📝 Питання:\n$question\n\n")
            append("📋 Варіанти:\n")
            answers.forEachIndexed { index, answer ->
                append("${index + 1}. $answer\n")
            }
            append("\n🤖 Підказка:\n$hint")
        }

        AlertDialog.Builder(this)
            .setTitle("Підказка від Gemini")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .setNeutralButton("Налаштування") { _, _ ->
                showApiKeyDialog()
            }
            .show()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    inner class WebAppInterface(private val context: Context) {
        @JavascriptInterface
        fun showToast(message: String) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
