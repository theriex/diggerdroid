package com.diggerhub.digger

import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.AssetManager
import android.content.res.AssetManager.ACCESS_BUFFER
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
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.appcompat.app.AppCompatActivity
import androidx.media.MediaBrowserServiceCompat
import androidx.webkit.WebViewAssetLoader
import com.diggerhub.digger.BuildConfig
import java.io.File
import java.io.InputStream
import java.net.URL
import java.net.HttpURLConnection
import java.text.SimpleDateFormat
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.Date
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    var dais = ""  //Digger Audio Information Summary
    val stateInfo = Bundle()  //player and deck state info for view rebuild
    val execsvc: ExecutorService = Executors.newFixedThreadPool(2)

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
        try {
            dwv.evaluateJavascript(jstxt, null)
        } catch(e: Exception) {
            Log.e("Digger", "eval js failed", e)
        }
    }

    override fun onCreate(inState: Bundle?) {
        super.onCreate(inState)
        inState?.run {  //restore state information if given
            stateInfo.putString("player", inState.getString("player", ""))
            stateInfo.putString("deck", inState.getString("deck", "")) }
        setContentView(R.layout.activity_main)
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/",
                            WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        val dwv: WebView = findViewById(R.id.webview)
        dwv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            } }
        dwv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                Log.d("Diggerjs", "${msg.message()} -- From line " +
                      "${msg.lineNumber()} of ${msg.sourceId()}")
                return true } }
        dwv.getSettings().setJavaScriptEnabled(true)
        dwv.addJavascriptInterface(DiggerAppInterface(this), "Android")
        Log.d("Digger", "Loading index.html")
        dwv.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState?.run {
            putString("player", stateInfo.getString("player", ""))
            putString("deck", stateInfo.getString("deck", "")) }
        super.onSaveInstanceState(outState)  //save parent view hierarchy
    }

    private fun jsonAV(attr: String, txt: String) : String {
        val nobs = txt.replace("\\", "\\\\")
        val noq = nobs.replace("\"", "\\\"")
        return "\"$attr\": \"$noq\""
    }

    private fun cstrval(cursor: Cursor, idx:Int) : String {
        val type = cursor.getType(idx)
        if(type == Cursor.FIELD_TYPE_NULL) {
            return "" }
        if(type == Cursor.FIELD_TYPE_INTEGER) {
            return cursor.getInt(idx).toString() }
        return cursor.getString(idx)
    }

    private fun queryAudio(fields:Map<String, String>) {
        val jsonsb = StringBuilder()
        val select = MediaStore.Audio.Media.IS_MUSIC + " != 0"
        val cols = fields.values.toTypedArray()
        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            cols, select, null, null
        )?.use { cursor ->
            while(cursor.moveToNext()) {
                if(jsonsb.isNotEmpty()) {
                    jsonsb.append(",")}
                jsonsb.append("{")
                fields.keys.forEachIndexed { idx, key ->
                    if(idx > 0) {
                        jsonsb.append(",") }
                    jsonsb.append(jsonAV(key, cstrval(cursor, idx))) }
                jsonsb.append("}") } }
        dais = "[" + jsonsb.toString() + "]"
    }

    private fun fetchMusicData() {
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


class DiggerAppInterface(private val context: MainActivity) {
    private val asi = DiggerAudioServiceInterface(context)
    private fun readFile(file: File) : String {
        file.createNewFile()  //create a new empty file if no file exists
        val inputStream: InputStream = file.inputStream()
        val text = inputStream.bufferedReader().use { it.readText() }
        return text
    }
    private fun writeFile(file: File, txt:String) {
        file.writeText(txt)
    }
    @JavascriptInterface
    fun getAppVersion() : String {
        return "v" + BuildConfig.VERSION_NAME
    }
    @JavascriptInterface
    fun readConfig() : String {
        return readFile(File(context.filesDir, "config.json"))
    }
    @JavascriptInterface
    fun writeConfig(cfgjson: String) {
        writeFile(File(context.filesDir, "config.json"), cfgjson)
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
    fun serviceInteraction(cmd: String, param: String, cnum: Int) {
        asi.svcexec(cmd, param, cnum)
    }
    @JavascriptInterface
    fun noteState(key: String, det: String) {
        context.stateInfo.putString(key, det)
    }
    @JavascriptInterface
    fun getRestoreState(key: String) : String {
        return context.stateInfo.getString(key, "")
    }
    @JavascriptInterface
    fun hubRequest(qname: String, reqnum: Int,
                   endpoint: String, verb: String, data: String) {
        try {
            val callinfo = HubWebRequest(context, qname, reqnum,
                                         endpoint, verb, data)
                context.execsvc.execute(callinfo)
        } catch(e: Exception) {
            Log.e("DiggerAppInterface", "Web request failed", e)
        }
    }
    @JavascriptInterface
    fun getAssetContent(path:String): String {
        val inputStream = context.getAssets().open(path, ACCESS_BUFFER)
        val text = inputStream.bufferedReader().use { it.readText() }
        return text
    }
}


class DiggerAudioServiceInterface(private val context: MainActivity) {
    //must start the service first before trying to bind to it, otherwise it
    //won't continue in the background.
    val dcn = ComponentName(context, "com.diggerhub.digger.DiggerAudioService")
    var command = ""
    var param = ""
    var reqnum = 0
    var dur = 0
    var pos = 0
    var state = ""    //"playing" or "paused"
    var commok = false

    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?,
                                        binder: IBinder?) {
            commok = true
            //Log.d("DiggerASI", "ServiceConnection connected")
            val digbind = binder as DiggerAudioService.DiggerBinder
            val das = digbind.getService()
            state = ""  //always recheck state
            if(das.mp == null) {
                dur = 0
                pos = 0 }
            else {  //have media player
                val mp = das.mp!!
                when(command) {
                    "status" -> {
                        das.dst = param }
                    "pause" -> {
                        //Log.d("DiggerASI", "state set to paused")
                        state = "paused"
                        mp.pause() }
                   "resume" -> {
                       //Log.d("DiggerASI", "state set to playing")
                       state = "playing"
                       mp.start() }
                   "seek" -> mp.seekTo(param.toInt())
                   else -> {
                       Log.d("DiggerASI", "unknown command " + command) } }
                dur = mp.getDuration()
                pos = mp.getCurrentPosition()
                if(dur - pos <= 4000) {  //if 4 secs or less from end
                    das.dst = "" }       //turn off service autoplay
                if(state.isEmpty()) {
                    Log.d("DiggerASI", "retrieving state from mp.isPlaying")
                    if(mp.isPlaying()) {
                        state = "playing" }
                    else {
                        state = "paused" } } }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d("Digger", "ServiceConnection disconnected")
        } }


    fun svcexec(svccommand:String, svcparam:String, callnum:Int) {
        //Log.d("DiggerASI", "svcexec " + svccommand)
        command = svccommand
        param = svcparam
        reqnum = callnum
        val exi = Intent("android.media.browse.MediaBrowserService")
        exi.setComponent(dcn)
        //BIND_IMPORTANT, BIND_NOT_FOREGROUND, BIND_WAIVE_PRIORITY
        val flags = 0
        val bindok = context.bindService(exi, connection, flags)
        context.unbindService(connection)  //always unbind even if bind failed
        if(!bindok) {
            Log.d("DiggerASI", "could not bind to service")
            state = ""; }  //return indeterminate state, caller can retry
        if(!commok) {
            //Log.d("DiggerASI", "binding service did not connect")
            state = ""; }  //return indeterminate state so caller can retry
        commok = false  //reset for next call
        val statobj = "{state:\"$state\", pos:$pos, dur:$dur, cc:$reqnum}"
        val callback = "app.svc.notePlaybackStatus($statobj)"
        context.runOnUiThread(Runnable() { context.djs(callback) })
    }

    fun playSong(path: String) {
        Log.d("DiggerASI", "playSong " + path)
        //launch DiggerAudioService via manifest intent-filter
        val exi = Intent("android.media.browse.MediaBrowserService",
                         Uri.fromFile(File(path)))
        exi.setComponent(dcn)
        try {  //if service already running, onStartCommand is called directly
            context.startService(exi)
            state = "playing"
        } catch(e: Exception) {
            //can't djs because still executing failed call
            Log.e("Digger", "playSong failed", e) }
    }
}


class DiggerAudioService : MediaBrowserServiceCompat(),
                    MediaPlayer.OnPreparedListener,
                    MediaPlayer.OnErrorListener,
                    MediaPlayer.OnCompletionListener {
    var mp: MediaPlayer? = null
    var dst = ""   //most recently received Digger deck state

    fun isostamp() : String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
        return sdf.format(Date())
    }

    fun playSong(pathuri: Uri) {
        val context = getApplicationContext()
        mp?.release()  //clean up any previously existing instance
        mp = MediaPlayer().apply {
            setDataSource(context, pathuri)
            setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            setOnPreparedListener(this@DiggerAudioService)
            setOnErrorListener(this@DiggerAudioService)
            setOnCompletionListener(this@DiggerAudioService)
            prepareAsync() }  //release calling thread
    }

    fun getNextSongPathFromState() : String {
        val dso = JSONObject(dst)
        val disp = dso.getString("disp")
        if(disp == "album") {
            val det = dso.getJSONObject("det")
            val info = det.getJSONObject("info")
            var ci = info.getInt("ci")
            ci += 1
            val songs = info.getJSONArray("songs")
            if(ci < songs.length()) {
                val song = songs.getJSONObject(ci)
                val path = song.getString("path")
                info.put("ci", ci)
                det.put("info", info)
                dso.put("det", det)
                dst = dso.toString()
                return path }
            return "" }
        val det = dso.getJSONArray("det")
        if(det.length() == 0) {
            return "" }
        val song = det.remove(0) as JSONObject
        val path = song.getString("path")
        dso.put("det", det)
        dst = dso.toString()
        return path
    }

    //should not collide with paused/stopped activity thread since autoplaying
    fun noteSongPlayed(path: String) {
        val ddf = File(getApplicationContext().filesDir, "digdat.json")
        val inputStream: InputStream = ddf.inputStream()
        val text = inputStream.bufferedReader().use { it.readText() }
        val dat = JSONObject(text)
        val songs = dat.getJSONObject("songs")
        val song = songs.getJSONObject(path)
        val pc = song.optInt("pc", 0)
        song.put("pc", pc + 1)
        song.put("lp", isostamp())
        songs.put(path, song)
        dat.put("songs", song)
        ddf.writeText(dat.toString())
    }

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
            dst = ""  //clear any previously saved deck state
            playSong(pathuri) }
        else {
            Log.d("DiggerAS", "onStartCommand null intent (restart)") }
        return android.app.Service.START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        mp?.release()
    }

    ////media player listener interface
    override fun onPrepared(mediaPlayer: MediaPlayer) {
        mp?.start()  //question mark is from doc example. Probably right.
    }

    override fun onError(ignore: MediaPlayer, what: Int, extra: Int): Boolean {
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

    override fun onCompletion(ignore: MediaPlayer) {
        //background Digger continues to next song, so this should only get
        //called if the activity was killed while the service continues.
        if(!dst.isEmpty()) {  //have state info, start autoplay
            Log.d("DiggerAudioService", "onCompletion autoplay " + isostamp())
            val path = getNextSongPathFromState()
            if(!path.isEmpty()) {  //have next song to play
                noteSongPlayed(path)
                playSong(Uri.fromFile(File(path))) } }
    }

    ////Required overrides for media content access
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


class HubWebRequest(private val context: MainActivity,
                    private val qname: String,
                    private val reqnum: Int,
                    private val endpoint: String,
                    private val verb: String,
                    private val data: String) : Runnable {
    override fun run() {
        val dhup = "https://diggerhub.com/api"
        var code = 503  //Service Unavailable
        var res = "Connection failed"
        try {
            Log.d("DiggerHub", "$qname $reqnum $verb /api$endpoint $data")
            val url = URL(dhup + endpoint)
            (url.openConnection() as? HttpURLConnection)?.run {
                setRequestProperty("Content-Type",
                                   "application/x-www-form-urlencoded")
                setRequestMethod(verb)
                setConnectTimeout(6 * 1000)
                setReadTimeout(20 * 1000)
                if(verb == "POST") {
                    setDoOutput(true)
                    getOutputStream().write(data.toByteArray()) }
                connect()  //ignored if POST already connected
                code = getResponseCode()
                if(code == 200) {
                    res = inputStream.bufferedReader().readText() }
                else {  //error code
                    try {
                        res = errorStream.bufferedReader().readText()
                    } catch(rx: Exception) {
                        Log.e("DiggerHubWebRequest", "errstream read error", rx)
                        res = responseMessage } } }
        } catch(e: Exception) {
            Log.e("DiggerHubWebRequest", "Call error", e)
        }
        res = res.replace("\\", "\\\\")  //escape contained backslashes
        res = res.replace("\"", "\\\"")  //escape contained quotes
        val cb = "app.svc.hubReqRes(\"$qname\",$reqnum,$code,\"$res\")"
        Log.d("DiggerHub", cb)
        context.runOnUiThread(Runnable() { context.djs(cb) })
    }
}
