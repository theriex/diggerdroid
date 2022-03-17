package com.diggerhub.digger

import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.Cursor
import android.media.MediaPlayer
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.MediaStore
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.util.Log
import android.webkit.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.appcompat.app.AppCompatActivity
import androidx.media.MediaBrowserServiceCompat
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.InputStream
import java.io.OutputStream


class MainActivity : AppCompatActivity() {
    var dais = ""  //Digger Audio Information Summary

    //Need to ask for READ_EXTERNAL_STORAGE interactively each time since it
    //can be revoked by the user.  Kicked off from requestMediaRead
    val prl = registerForActivityResult(RequestPermission()) { isGranted ->
        var stat = ""
        if(isGranted) {
            fetchMusicData() }
        else {
            stat = "READ_EXTERNAL_STORAGE permission denied." }
        djs("app.svc.mediaReadComplete($stat)") }

    //digger javascript to run in the webview
    fun djs(jstxt: String) {
        //Log.d("Digger", "djs: " + jstxt)
        val dwv: WebView = findViewById(R.id.webview)
        dwv.evaluateJavascript(jstxt, null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/",
                            WebViewAssetLoader.AssetsPathHandler(this))
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
                Log.d("Diggerjs", "${msg.message()} -- From line " +
                      "${msg.lineNumber()} of ${msg.sourceId()}")
                return true } }
        dwv.getSettings().setJavaScriptEnabled(true)
        dwv.addJavascriptInterface(WebAppInterface(this), "Android")
        Log.d("Digger", "Loading index.html")
        dwv.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    fun jsonAV(attr: String, txt: String) : String {
        val nobs = txt.replace("\\", "\\\\")
        val noq = nobs.replace("\"", "\\\"")
        return "\"$attr\": \"$noq\""
    }

    fun cstrval(cursor: Cursor, idx:Int) : String {
        val type = cursor.getType(idx)
        if(type == Cursor.FIELD_TYPE_NULL) {
            return "" }
        if(type == Cursor.FIELD_TYPE_INTEGER) {
            return cursor.getInt(idx).toString() }
        return cursor.getString(idx)
    }

    fun queryAudio(fields:Map<String, String>) {
        val jsonsb = StringBuilder()
        val select = MediaStore.Audio.Media.IS_MUSIC + " != 0";
        val cols = fields.values.toTypedArray()
        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            cols, select, null, null
        )?.use { cursor ->
            while(cursor.moveToNext()) {
                if(jsonsb.length > 0) {
                    jsonsb.append(",")}
                jsonsb.append("{")
                fields.keys.forEachIndexed { idx, key ->
                    if(idx > 0) {
                        jsonsb.append(",") }
                    jsonsb.append(jsonAV(key, cstrval(cursor, idx))) }
                jsonsb.append("}") } }
        dais = "[" + jsonsb.toString() + "]"
    }

    fun fetchMusicData() {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            queryAudio(mapOf(
                "title" to MediaStore.Audio.Media.TITLE,
                "artist" to MediaStore.Audio.Media.ARTIST,
                "album" to MediaStore.Audio.Media.ALBUM,
                "track" to MediaStore.Audio.Media.TRACK,
                "discnum" to MediaStore.Audio.Media.DISC_NUMBER,
                "relpath" to MediaStore.Audio.Media.RELATIVE_PATH,
                "dispname" to MediaStore.Audio.Media.DISPLAY_NAME)) }
        else {
            queryAudio(mapOf(
                "title" to MediaStore.Audio.Media.TITLE,
                "artist" to MediaStore.Audio.Media.ARTIST,
                "album" to MediaStore.Audio.Media.ALBUM,
                "track" to MediaStore.Audio.Media.TRACK,
                "data" to MediaStore.Audio.Media.DATA)) }
    }

    fun requestMediaRead() {
        prl.launch(READ_EXTERNAL_STORAGE)
    }
}


class WebAppInterface(private val context: MainActivity) {
    val asi = AudioServiceInterface(context)
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
    @JavascriptInterface
    fun playSong(path: String) {
        asi.playSong(path)
    }
    @JavascriptInterface
    fun requestStatusUpdate() {
        asi.svcexec("status", "")
    }
    @JavascriptInterface
    fun pause() {
        asi.svcexec("pause", "")
    }
    @JavascriptInterface
    fun resume() {
        asi.svcexec("resume", "")
    }
    @JavascriptInterface
    fun seek(ms: Int) {
        asi.svcexec("seek", ms.toString())
    }
}


class AudioServiceInterface(private val context: MainActivity) {
    //must start the service first before trying to bind to it, otherwise it
    //won't continue in the background.
    val dcn = ComponentName(context, "com.diggerhub.digger.DiggerAudioService")
    var command = ""
    var param = ""
    var dur = 0
    var pos = 0
    var state = ""    //"playing" or "paused"

    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?,
                                        binder: IBinder?) {
            val digbind = binder as DiggerAudioService.DiggerBinder
            val das = digbind.getService()
            if(das.mp == null) {
                dur = 0
                pos = 0
                state = "" }
            else {
                val mp = das.mp!!
                when(command) {
                    "pause" -> mp.pause()
                    "resume" -> mp.start()
                    "seek" -> mp.seekTo(param.toInt()) }
                dur = mp.getDuration()
                pos = mp.getCurrentPosition()
                if(mp.isPlaying()) {
                    state = "playing" }
                else {
                    state = "paused" } }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d("Digger", "ServiceInterface disconnected")
        }
    }

    fun svcexec(svccommand:String, svcparam:String) {
        command = svccommand
        param = svcparam
        val exi = Intent("android.media.browse.MediaBrowserService")
        exi.setComponent(dcn)
        //BIND_IMPORTANT, BIND_NOT_FOREGROUND, BIND_WAIVE_PRIORITY
        val flags = 0
        val bound = context.bindService(exi, connection, flags)
        context.unbindService(connection)  //always unbind even binding failed
        if(bound) {
            //Log.d("DiggerASI", "bind service succeeded")
            val statobj = "{state:\"$state\", pos:$pos, dur:$dur}"
            val callback = "app.svc.notePlaybackStatus($statobj)"
            context.runOnUiThread(Runnable { context.djs(callback) }) }
        else {
            Log.d("DiggerASI", "could not bind to service") }
    }

    fun playSong(path: String) {
        Log.d("DiggerASI", "playSong " + path)
        //launch DiggerAudioService via manifest intent-filter
        val exi = Intent("android.media.browse.MediaBrowserService",
                         Uri.fromFile(File(path)))
        exi.setComponent(dcn)
        try {  //if service already running, onStartCommand is called directly
            context.startService(exi)
        } catch(e: Exception) {
            //can't djs because still executing failed call
            Log.e("Digger", "playSong failed", e) }
    }
}


class DiggerAudioService : MediaBrowserServiceCompat(),
                    MediaPlayer.OnPreparedListener,
                    MediaPlayer.OnErrorListener {
    var mp: MediaPlayer? = null

    val binder = DiggerBinder()
    inner class DiggerBinder: Binder() {
        fun getService(): DiggerAudioService {
            return this@DiggerAudioService }
    }
    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        //intent.action is declared in manifest and used by startService
        if(intent != null) {  //might be null if restarted after being killed
            val pathuri = intent.data!!  //force Uri? to Uri
            Log.d("DiggerAS", "onStartCommand pathuri: " + pathuri)
            val context = getApplicationContext()
            mp?.release()  //clean up any previously existing instance
            mp = MediaPlayer().apply {
                setDataSource(context, pathuri);
                setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
                setOnPreparedListener(this@DiggerAudioService)
                setOnErrorListener(this@DiggerAudioService)
                prepareAsync() } }  //release main thread
        else {
            Log.d("DiggerAS", "onStartCommand null intent (restart)") }
        return android.app.Service.START_STICKY
    }

    override fun onPrepared(mediaPlayer: MediaPlayer) {
        mp?.start()  //question mark is from doc example. Probably right.
    }

    override fun onDestroy() {
        super.onDestroy()
        mp?.release()
    }

    override fun onError(mp: MediaPlayer, what: Int, extra: Int): Boolean {
        val stat = when(what) {
            MediaPlayer.MEDIA_ERROR_SERVER_DIED -> "Server died"
            else -> "Unknown" }
        val err = when(extra) {
            MediaPlayer.MEDIA_ERROR_IO -> "I/O Error"
            MediaPlayer.MEDIA_ERROR_MALFORMED -> "Malformed"
            MediaPlayer.MEDIA_ERROR_UNSUPPORTED -> "Unsupported"
            MediaPlayer.MEDIA_ERROR_TIMED_OUT -> "Timed out"
            -2147483648 -> "System failure"
            else -> "No further details" }
        Log.e("DiggerAudioService", "onError: $stat, $err")
        return false  //triggers call to OnCompletionListener
    }

    /* Required overrides for media content access */
    override fun onGetRoot(@NonNull clientPackageName: String, clientUid: Int,
                           @Nullable rootHints: Bundle?) : BrowserRoot? {
        return null
    }
    override fun onLoadChildren(@NonNull parentId: String,
                                @NonNull result: Result<MutableList<MediaItem>>
                                ): Unit {
        result.sendResult(mutableListOf<MediaItem>())
    }
}
