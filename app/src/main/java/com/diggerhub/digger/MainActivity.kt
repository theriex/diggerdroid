package com.diggerhub.digger

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.webkit.*
import androidx.webkit.WebViewAssetLoader
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.database.Cursor
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class MainActivity : AppCompatActivity() {
    var dais = ""  //Digger Audio Information Summary

    val prl = registerForActivityResult(RequestPermission()) { isGranted ->
        var stat = ""
        if(isGranted) {
            fetchMusicData() }
        else {
            stat = "READ_EXTERNAL_STORAGE permission denied." }
        stat = "app.svc.mediaReadComplete($stat)"
        val dwv: WebView = findViewById(R.id.webview)
        dwv.evaluateJavascript(stat, null) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build();
        val dwv: WebView = findViewById(R.id.webview)
        dwv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url);
            } }
        dwv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                Log.d("Digger", "${msg.message()} -- From line " +
                      "${msg.lineNumber()} of ${msg.sourceId()}")
                return true } }
        dwv.getSettings().setJavaScriptEnabled(true)
        dwv.addJavascriptInterface(WebAppInterface(this), "Android")
        dwv.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    fun jsonAV(attr: String, txt: String) : String {
        val encval = java.net.URLEncoder.encode(txt, "utf-8")
        return "\"$attr\": \"$encval\""
    }

    fun cstrval(cursor: Cursor, idx:Int) : String {
        val type = cursor.getType(idx)
        if(type == Cursor.FIELD_TYPE_NULL) {
            return "" }
        if(type == Cursor.FIELD_TYPE_INTEGER) {
            return cursor.getInt(idx).toString() }
        return cursor.getString(idx)
    }

    fun fetchMusicData() {
        val jsonsb = StringBuilder()
        val select = MediaStore.Audio.Media.IS_MUSIC + " != 0";
        val cols = arrayOf(  //DISC_NUMBER column not available
            MediaStore.Audio.Media.TRACK,        //Integer
            MediaStore.Audio.Media.TITLE,        //String
            MediaStore.Audio.Media.ARTIST,       //String
            MediaStore.Audio.Media.ALBUM)        //String
        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            cols, select, null, null
        )?.use { cursor ->
            while(cursor.moveToNext()) {
                if(jsonsb.length > 0) {
                    jsonsb.append(",")}
                jsonsb.append("{" +
                        jsonAV("track", cstrval(cursor, 0)) + "," +
                        jsonAV("title", cstrval(cursor, 1)) + "," +
                        jsonAV("artist", cstrval(cursor, 2)) + "," +
                        jsonAV("album", cstrval(cursor, 3)) + "}") } }
        dais = "[" + jsonsb.toString() + "]"
    }

    fun requestMediaRead() {
        prl.launch(READ_EXTERNAL_STORAGE)
    }
}

class WebAppInterface(private val context: MainActivity) {
    fun readFile(file: File) : String {
        file.createNewFile()  //create a new empty file if no file exists
        val inputStream: InputStream = file.inputStream()
        val text = inputStream.bufferedReader().use { it.readText() }
        return text
    }
    fun writeFile(file: File, txt:String) {
        file.writeText(txt)
    }
    @JavascriptInterface
    fun readConfig() : String {
        return readFile(File(context.filesDir, ".digger_config.json"))
    }
    @JavascriptInterface
    fun writeConfig(cfgjson: String) {
        writeFile(File(context.filesDir, ".digger_config.json"), cfgjson)
    }
    @JavascriptInterface
    fun readDigDat() : String {
        return readFile(File(context.filesDir, "digdat.json"))
    }
    @JavascriptInterface
    fun writeDigDat(dbjson: String) {
        writeFile(File(context.filesDir, "digdat.json"), dbjson)
    }
    @JavascriptInterface
    fun requestMediaRead()  {
        context.requestMediaRead()
    }
    @JavascriptInterface
    fun getAudioItemSummary() : String {
        return context.dais
    }
}
