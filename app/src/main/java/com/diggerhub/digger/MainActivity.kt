package com.diggerhub.digger

import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.app.Notification
import android.app.Service
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
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
import androidx.core.app.NotificationCompat
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
    var uivs = "initializing"  //UI visibility status
    var dais = ""  //Digger Audio Information Summary
    lateinit var jsai: DiggerAppInterface
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
        jsai = DiggerAppInterface(this)
        dwv.addJavascriptInterface(jsai, "Android")
        Log.d("Digger", "Loading index.html")
        dwv.loadUrl("https://appassets.androidplatform.net/assets/index.html")
        uivs = "visible"
    }

    override fun onStart() {
        super.onStart()
        uivs = "visible"
    }

    override fun onResume() {
        super.onResume()
        uivs = "visible"
    }

    override fun onPause() {
        super.onPause()
        uivs = "visible"  //webview updates still visible if split screen
        jsai.decoupleService()
    }

    override fun onStop() {
        super.onStop()
        uivs = "visible"  //webview updates can still happen in background
        jsai.decoupleService()
    }

    override fun onDestroy() {
        if(isFinishing()) {  //not just a rotation, really going away
            Log.d("Digger", "onDestroy isFinishing, stopping service")
            //stopService is defined in inherited android.content.Context
            val exi = Intent("com.diggerhub.digger.DiggerAudioService")
            val dcn = ComponentName(this,
                                    "com.diggerhub.digger.DiggerAudioService")
            exi.setComponent(dcn)
            stopService(exi) }
        uivs = "destroyed"
        super.onDestroy()
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
        Log.d("Digger", "queryAudio result: " + dais)
    }

    private fun fetchMusicData() {
        Log.d("Digger", "Build.VERSION.SDK_INT: " + Build.VERSION.SDK_INT +
              " >= Build.VERSION_CODES.R " +
              (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R))
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            queryAudio(mapOf(
                "title" to MediaStore.Audio.Media.TITLE,
                "artist" to MediaStore.Audio.Media.ARTIST,
                "album" to MediaStore.Audio.Media.ALBUM,
                "track" to MediaStore.Audio.Media.TRACK,
                "discnum" to MediaStore.Audio.Media.DISC_NUMBER,
                "data" to MediaStore.Audio.Media.DATA,
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

/***************************************************
*
* Pathways from the Digger web app back into supporting code.
*
***************************************************/
class DiggerAppInterface(private val context: MainActivity) {
    private val asi = DiggerAudioServiceInterface(context)
    fun decoupleService() {
        asi.decoupleService()
    }
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
    @JavascriptInterface
    fun copyToClipboard(text:String): String {
        var retval = text
        try {
            val clipsvc = context.getSystemService(Context.CLIPBOARD_SERVICE)
            val clipmgr = clipsvc as ClipboardManager
            val clipdat = ClipData.newPlainText("label", text)
            clipmgr.setPrimaryClip(clipdat)
        } catch(e: Exception) {
            Log.e("DiggerAppInterface", "Web request failed", e)
            retval = ""
        }
        return retval
    }
}


/***************************************************
*
* Connect from the UI to the foreground music player service.  A separate
* foreground service is required for the music to keep playing when the
* activity is in the background.
*
***************************************************/
class DiggerAudioServiceInterface(private val context: MainActivity) {
    val dcn = ComponentName(context, "com.diggerhub.digger.DiggerAudioService")
    var svccn = dcn
    var svcbinder: IBinder? = null
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
            svccn = name ?: svccn
            svcbinder = binder
            commok = true
            //Log.d("DiggerASI", "ServiceConnection connected")
            val digbind = binder as DiggerAudioService.DiggerBinder
            val das = digbind.getService()
            state = ""  //always recheck state
            if(!das.failmsg.isEmpty()) {  //note crash
                Log.d("DiggerASI", "das.failmsg: " + das.failmsg)
                state = "failed " + das.failmsg }
            else if(das.mp == null) {  //no player yet, caller retries
                dur = 0
                pos = 0 }
            else {  //have media player
                try {
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
                            state = "paused" } }
                } catch(e: Exception) {
                    state = ""  //return indeterminate if anything went wrong
                    Log.e("Digger", "ServiceConnection mp failure ", e)
                } }
        }  //end onServiceConnected
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d("Digger", "ServiceConnection disconnected")
        } }


    fun decoupleService() {
        if(svcbinder != null) {
            context.unbindService(connection)
            var svccn = dcn
            svcbinder = null }
    }


    //Returning an empty string indeterminate state value is an indication
    //the call couldn't be completed and the caller should retry.
    fun svcexec(svccommand:String, svcparam:String, callnum:Int) {
        //Log.d("DiggerASI", "svcexec " + svccommand)
        command = svccommand
        param = svcparam
        reqnum = callnum
        if(context.uivs != "visible") {
            state = "failed: MainActivity " + context.uivs }
        else {  //UI is visible
            if(svcbinder != null) {
                connection.onServiceConnected(svccn, svcbinder) }
            else {
                val exi = Intent("com.diggerhub.digger.DiggerAudioService")
                exi.setComponent(dcn)
                //BIND_IMPORTANT, BIND_NOT_FOREGROUND, BIND_WAIVE_PRIORITY
                val flags = 0
                val bindok = context.bindService(exi, connection, flags)
                if(!bindok) {
                    Log.d("DiggerASI", "could not bind to service")
                    context.unbindService(connection)  //unbind just in case
                    state = ""; } } //return indeterminate
            if(!commok) {
                decoupleService()
                //Log.d("DiggerASI", "binding service did not connect")
                state = ""; } }  //return indeterminate state
        commok = false  //reset for next call
        val statobj = "{state:\"$state\", pos:$pos, dur:$dur, cc:$reqnum}"
        val callback = "app.svc.notePlaybackStatus($statobj)"
        context.runOnUiThread(Runnable() { context.djs(callback) })
    }


    fun playSong(path: String) {
        Log.d("DiggerASI", "playSong " + path)
        var songuri = Uri.fromFile(File(path))
        //launch DiggerAudioService via manifest intent-filter
        val exi = Intent("com.diggerhub.digger.DiggerAudioService", songuri)
        exi.setComponent(dcn)
        try {
            //If the service already running, onStartCommand is called directly.
            //Don't need to call startForegroundService because only launching
            //when app is in the foreground.
            context.startService(exi)
            state = "playing"
        } catch(e: Exception) {
            //can't djs because still executing failed call
            Log.e("Digger", "playSong failed", e) }
    }
}


/***************************************************
*
* Digger music player service.  Prior to Oreo (API level 26 releaased 2017),
* you just started a service and it continued even when the app was not in
* the foreground.  With Oreo, you have to start a foreground service or the
* service will be stopped after a few seconds of the app losing focus.
*
***************************************************/
class DiggerAudioService : Service(),
                    MediaPlayer.OnPreparedListener,
                    MediaPlayer.OnErrorListener,
                    MediaPlayer.OnCompletionListener {
    var mp: MediaPlayer? = null
    var svcntf: Notification? = null
    var dst = ""   //most recently received Digger deck state
    var failmsg = ""

    fun isostamp() : String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
        return sdf.format(Date())
    }

    fun dasPlaySong(pathuri: Uri) {
        val context = getApplicationContext()
        mp?.release()  //clean up any previously existing instance
        mp = MediaPlayer().apply {
            dst = ""  //clear any previously saved deck state
            failmsg = ""  //clear any previous failure
            try {
                setDataSource(context, pathuri)
                setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
                setOnPreparedListener(this@DiggerAudioService)
                setOnErrorListener(this@DiggerAudioService)
                setOnCompletionListener(this@DiggerAudioService)
                prepareAsync()  //release calling thread
            } catch(e: Exception) {
                //leave dst cleared, app will resend
                Log.e("DiggerAS", "onStartCommand dasPlaySong failed.", e)
                failmsg = "DiggerAudioService.onStartCommand path " + pathuri
                Log.d("DiggerAS", "failmsg: " + failmsg)
            } }
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

    override fun onCreate() {
        super.onCreate();
        val context = getApplicationContext()
        val ntfi = Intent(".MainActivity")
        //"if a task is already running for the activity you are now starting,
        //then a new activity will not be started; instead, the current task
        //will simply be brought to the front of the screen with the state it
        //was last in."
        ntfi.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        //if the described PendingIntent already exists, then keep it but
        //replace its extra data with what is in this new Intent
        val piflags = (PendingIntent.FLAG_UPDATE_CURRENT or
        //Targeting S+ (version 31 and above) requires mutability flag
                       PendingIntent.FLAG_IMMUTABLE)
        val pndi = PendingIntent.getActivity(context,
                                             0,   //requestCode
                                             ntfi,
                                             piflags)
        NotificationCompat.Builder(context, "DiggerSvcChan").apply {
            setContentTitle("Digger Music Service")
            setContentText("Playing music matching your retrieval settings")
            setSmallIcon(R.mipmap.ic_launcher)
            setContentIntent(pndi)
            svcntf = build() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        //intent.action is declared in manifest and used by startService
        if(intent != null) {
            val pathuri = intent.data!!  //force Uri? to Uri
            Log.d("DiggerAS", "onStartCommand pathuri: " + pathuri)
            dasPlaySong(pathuri) }
        else {  //null, service restarted after having been killed
            Log.d("DiggerAS", "onStartCommand null intent (restart)") }
        startForeground(1, svcntf)
        return Service.START_STICKY  //please restart service after killing it
    }

    override fun onDestroy() {
        super.onDestroy()
        mp?.release()
        mp = null
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
                dasPlaySong(Uri.fromFile(File(path))) }
            else {
                Log.i("DiggerAudioService", "No path for next song, quitting.")
                stopSelf() } }
        else {
            Log.i("DiggerAudioService", "No deck state, quitting.")
            stopSelf() }
    }

}


/***************************************************
*
* Connect from the UI to DiggerHub.
*
***************************************************/
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
