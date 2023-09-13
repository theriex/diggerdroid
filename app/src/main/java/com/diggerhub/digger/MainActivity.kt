package com.diggerhub.digger

import android.Manifest.permission.READ_MEDIA_AUDIO
import android.app.Notification
import android.app.NotificationManager
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
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import android.view.KeyEvent
import android.webkit.*
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import androidx.webkit.WebViewAssetLoader
import com.diggerhub.digger.BuildConfig
import java.io.File
import java.io.InputStream
import java.net.URL
import java.net.HttpURLConnection
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.Date
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    val lognm = "DiggerMain"
    var uivs = "initializing"  //UI visibility status
    var dais = ""  //Digger Audio Information Summary
    lateinit var jsai: DiggerAppInterface
    val stateInfo = Bundle()  //player and deck state info for view rebuild
    val execsvc: ExecutorService = Executors.newFixedThreadPool(2)

    //Need to ask for READ_MEDIA_AUDIO interactively each time since it
    //can be revoked by the user.  Kicked off from requestMediaRead
    val prl = registerForActivityResult(RequestPermission()) { isGranted ->
        var stat = ""
        if(isGranted) {
            fetchMusicData() }
        else {
            stat = "READ_MEDIA_AUDIO permission denied." }
        djs("app.svc.mediaReadComplete(\"$stat\")") }

    //digger javascript to run in the webview
    fun djs(jstxt: String) {
        Log.d(lognm, "djs: " + jstxt)
        val dwv: WebView = findViewById(R.id.webview)
        try {
            dwv.evaluateJavascript(jstxt, null)
        } catch(e: Exception) {
            Log.e(lognm, "eval js failed", e)
        }
    }

    override fun onCreate(inState: Bundle?) {
        super.onCreate(inState)
        Log.d(lognm, "MainActivity onCreate *******************************")
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
        Log.d(lognm, "Loading index.html")
        dwv.loadUrl("https://appassets.androidplatform.net/assets/index.html")
        uivs = "visible"
    }

    override fun onStart() {
        super.onStart()
        Log.d(lognm, "MainActivity onStart called")
        uivs = "visible"
        val dwv: WebView = findViewById(R.id.webview)
        dwv.resumeTimers()
    }

    override fun onResume() {
        super.onResume()
        Log.d(lognm, "MainActivity onResume called")
        uivs = "visible"
        //service still coupled and timers still active
    }

    override fun onPause() {
        super.onPause()
        Log.d(lognm, "MainActivity onPause called")
        uivs = "visible"  //webview updates still visible if split screen
        //leave service coupled for progress updates and responsive controls
        //do not pauseTimers. UI progress updates continue to be displayed.
    }

    override fun onStop() {
        super.onStop()
        Log.d(lognm, "MainActivity onStop called")
        uivs = "visible"  //webview still considered visible, allow calls
        //reduce load to minimum, player service is essentially on its own.
        jsai.decoupleService()
        val dwv: WebView = findViewById(R.id.webview)
        dwv.pauseTimers()
    }

    override fun onDestroy() {
        Log.d(lognm, "MainActivity onDestroy called")
        if(isFinishing()) {  //not just a rotation, really going away
            Log.d(lognm, "onDestroy isFinishing, stopping service")
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
        Log.d(lognm, "queryAudio result: " + dais)
    }

    private fun fetchMusicData() {
        Log.d(lognm, "Build.VERSION.SDK_INT: " + Build.VERSION.SDK_INT +
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
        prl.launch(READ_MEDIA_AUDIO)
    }
}

/***************************************************
*
* Pathways from the Digger web app back into supporting code.
*
***************************************************/
class DiggerAppInterface(private val context: MainActivity) {
    val lognm = "DiggerAppInterface"
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
    fun getVersionCode() : String {
        var bc = BuildConfig.VERSION_CODE
        return "build " + bc.toString()
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
            Log.e(lognm, "Web request failed", e)
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
            Log.e(lognm, "Web request failed", e)
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
    val lognm = "DiggerASI"
    val dcn = ComponentName(context, "com.diggerhub.digger.DiggerAudioService")
    var svccn = dcn   //service connection name
    var svcbinder: IBinder? = null
    var command = ""
    var param = ""
    var reqnum = 0
    var dur = 0
    var pos = 0
    var path = ""
    var dbts = "1970-01-01T:00:00:00.00Z"
    var sipbst = ""    //service interface playback state
    var commok = false

    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?,
                                        binder: IBinder?) {
            svccn = name ?: svccn
            svcbinder = binder
            commok = true
            //Log.d(lognm, "ServiceConnection connected")
            val digbind = binder as DiggerAudioService.DiggerBinder
            val das = digbind.getService()
            das.cts = System.currentTimeMillis()  //update UI comm timestamp
            sipbst = ""  //always recheck state
            if(!das.failmsg.isEmpty()) {  //note crash
                Log.d(lognm, "das.failmsg: " + das.failmsg)
                sipbst = "failed " + das.failmsg }
            else if(das.mp == null) {  //no player yet, caller retries
                path = ""
                dur = 0
                pos = 0 }
            else {  //have media player
                try {
                    val mp = das.mp!!
                    when(command) {
                        "status" -> {
                            das.updateQueue(param)
                            das.verifyNotice() }
                        "pause" -> {
                            sipbst = "paused"
                            mp.pause() }
                       "resume" -> {
                           sipbst = "playing"
                           mp.start() }
                       "seek" -> mp.seekTo(param.toInt())
                       else -> {
                           Log.d(lognm, "unknown command " + command) } }
                    path = das.playpath
                    dur = mp.getDuration()
                    pos = mp.getCurrentPosition()
                    dbts = das.dbts
                    if(sipbst.isEmpty()) {  //figure out sipbst from service
                        if(das.pbstate == "ended") {
                            sipbst = "ended" }
                        else if(mp.isPlaying()) {
                            sipbst = "playing" }
                        else {
                            sipbst = "paused" } }
                } catch(e: Exception) {
                    sipbst = ""  //return indeterminate if anything went wrong
                    Log.e(lognm, "ServiceConnection mp failure ", e)
                } }
        }  //end onServiceConnected
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(lognm, "ServiceConnection disconnected")
        } }


    fun decoupleService() {
        if(svcbinder != null) {
            context.unbindService(connection)
            var svccn = dcn
            svcbinder = null }
    }


    //Returning an indeterminate state value empty string is an indication
    //the call couldn't be completed and the caller should retry.
    fun svcexec(svccommand:String, svcparam:String, callnum:Int) {
        //Log.d(lognm, "svcexec " + svccommand)
        command = svccommand
        param = svcparam
        reqnum = callnum
        if(context.uivs != "visible") {
            sipbst = "failed: MainActivity " + context.uivs }
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
                    Log.d(lognm, "could not bind to service")
                    context.unbindService(connection)  //unbind just in case
                    sipbst = ""; } }
            if(!commok) {
                decoupleService()
                //Log.d(lognm, "binding service did not connect")
                sipbst = ""; } }
        commok = false  //reset indicator flag for next call
        val encpath = java.net.URLEncoder.encode(path, "utf-8")
        val statobj = ("{state:\"$sipbst\", pos:$pos, dur:$dur" +
                       ", path:\"$encpath\", cc:$reqnum, dbts:\"$dbts\"}")
        val callback = "app.svc.notePlaybackStatus($statobj)"
        Log.d(lognm, "callback: " + callback)
        context.runOnUiThread(Runnable() { context.djs(callback) })
    }


    fun playSong(path: String) {
        Log.d(lognm, "playSong " + path)
        //Log.d(lognm, "playSong File: " + File(path))
        var songuri = Uri.fromFile(File(path))
        //Log.d(lognm, "playSong URI: " + songuri)
        //launch DiggerAudioService via manifest intent-filter
        val exi = Intent("com.diggerhub.digger.DiggerAudioService", songuri)
        exi.setComponent(dcn)
        try {
            //If the service already running, onStartCommand is called directly.
            //Don't need to call startForegroundService because only launching
            //when app is in the foreground.
            context.startService(exi)
            sipbst = "playing"
        } catch(e: Exception) {
            //can't djs because still executing failed call
            Log.e(lognm, "playSong failed", e) }
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
    val lognm = "DiggerAudioService"
    val svcNoticeId = 1   //1 is used in most if not all sample code
    val defaultNoticeText = "Playing your matching songs."
    var mp: MediaPlayer? = null
    lateinit var mse: MediaSessionCompat  //session has same lifespan as service
    //create a callback object extending the required abstract base class
    val mscb: MediaSessionCompat.Callback = object: MediaSessionCompat.Callback() {  //support pause button on bluetooth headsets
        override fun onMediaButtonEvent(mbi: Intent): Boolean {
            val action = mbi.getAction()
            val keyevt: KeyEvent? = mbi.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
            Log.d(lognm, "das mscb action: " + action + ", keyevt: " + keyevt)
            if(action == Intent.ACTION_MEDIA_BUTTON) {
                if(keyevt?.action == KeyEvent.ACTION_UP) {
                    if(pbstate != "ended") {  //not sleeping
                        togglePlayPause() } } }
            return super.onMediaButtonEvent(mbi)
        }
    }
    //Playback state variables accessed locally and by DiggerASI
    var pbstate = "init"  //"playing"/"paused"/"ended"/"failed"
    var dbts = "1970-01-01T:00:00:00.00Z"  //last database read
    var dst = ""   //most recently received Digger deck state
    var cts = System.currentTimeMillis()  //most recent UI communication time
    var failmsg = ""
    var playpath = ""

    fun togglePlayPause() {
        var state = "unknown"
        if(mp != null) {
            val mpref = mp!!
            if(mpref.isPlaying()) {
                mpref.pause()
                state = "paused" }
            else {
                mpref.start()
                state = "playing" } }
        Log.d(lognm, "togglePlayPause state: " + state)
    }

    fun isostamp() : String {
        val now = java.time.Instant.now()
        val ts = java.time.format.DateTimeFormatter.ISO_INSTANT.format(now)
        return ts;
    }

    //Called from the UI, or MediaPlayer.OnCompletion.  When initially
    //launching a service, only the song URI is available.  The deck state
    //info (dst) is transferred a few seconds afterwards via binding when
    //the app tickf requests playback state.  Until dst is available, the
    //service has no app information about the song other than the URI.
    fun dasPlaySong(pathuri: Uri) {
        val context = getApplicationContext()
        mp?.release()  //clean up any previously existing instance
        mp = MediaPlayer().apply {
            cts = System.currentTimeMillis()  //update UI communication time
            failmsg = ""  //clear any previous failure
            try {
                playpath = pathuri.toString();
                setDataSource(context, pathuri)
                setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
                setOnPreparedListener(this@DiggerAudioService)
                setOnErrorListener(this@DiggerAudioService)
                setOnCompletionListener(this@DiggerAudioService)
                prepareAsync()  //release calling thread
                pbstate = "playing"
            } catch(e: Exception) {
                playpath = ""
                Log.e(lognm, "onStartCommand dasPlaySong failed.", e)
                failmsg = ("DiggerAudioService.onStartCommand path: " +
                           pathuri + ", msg: " + e.message)
                Log.d(lognm, "failmsg: " + failmsg)
                pbstate = "failed"
            } }
    }

    fun getNextAlbumSongPath(dso: JSONObject) : String {
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
            Log.d(lognm, "  album path: " + path)
            return path }
        Log.d(lognm, "  No songs left on album")
        return ""
    }

    fun getNextDeckSongPath(dso: JSONObject) : String {
        val det = dso.getJSONArray("det")
        Log.d(lognm, "  det.length: " + det.length())
        if(det.length() == 0) {
            Log.d(lognm, "  No songs left on deck")
            return "" }
        val song = det.remove(0) as JSONObject
        val path = song.getString("path")
        dso.put("det", det)
        dso.put("npsi", song)
        dst = dso.toString()
        Log.d(lognm, "  deck path: " + path)
        return path
    }

    fun getNextSongPathFromState() : String {
        val dso = JSONObject(dst)
        val disp = dso.getString("disp")
        Log.d(lognm, "getNextSongPathFromState disp: " + disp)
        if(disp == "album") {
            return getNextAlbumSongPath(dso) }
        return getNextDeckSongPath(dso)
    }

    //should not collide with paused/stopped activity thread since autoplaying
    fun noteSongPlayed(path: String) {
        Log.d(lognm, "noteSongPlayed " + path)
        val ddf = File(getApplicationContext().filesDir, "digdat.json")
        val inputStream: InputStream = ddf.inputStream()
        val text = inputStream.bufferedReader().use { it.readText() }
        val dat = JSONObject(text)
        val songs = dat.getJSONObject("songs")
        val song = songs.getJSONObject(path)
        val pc = song.optInt("pc", 0)
        song.put("pc", pc + 1)
        dbts = isostamp()  //note database update time for app UI sync
        song.put("lp", dbts)
        Log.d(lognm, "    lp: " + song["lp"])
        songs.put(path, song)
        dat.put("songs", songs)
        ddf.writeText(dat.toString(2))  //human readable 2 indent spaces
    }

    //create the required ForegroundService Notification and return it.
    fun makeFgSvcN(text: String) : Notification {
        var svcntf: Notification
        val context = getApplicationContext()
        val pndi: PendingIntent =
            Intent(context, MainActivity::class.java).let { intent ->
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP);
                PendingIntent.getActivity(context, 0, intent,
                                          (PendingIntent.FLAG_IMMUTABLE or
                                           PendingIntent.FLAG_UPDATE_CURRENT)) }
        //DiggerSvcChan is a unique channel name created in DiggerApp.
        NotificationCompat.Builder(context, "DiggerSvcChan").apply {
            setContentTitle("Digger Music Service")
            setContentText(text)
            setSmallIcon(R.mipmap.ic_launcher)
            setContentIntent(pndi)
            svcntf = build() }
        return svcntf
    }

    fun updateQueue(qstatstr: String) {
        val qstat = JSONObject(qstatstr)
        val qdbt = qstat.optString("dbts", "1970-01-01T:00:00:00.00Z")
        if(qdbt.compareTo(dbts) >= 0) {
            dbts = qdbt
            dst = (qstat.getJSONObject("deck")).toString()
            Log.d(lognm, "updateQueue dst updated, qdbt " + qdbt + " >= " +
                             "dbts: " + dbts + ", qstatstr: " + qstatstr) }
        else {  //stale data from outdated app interface coming back to life
            Log.d(lognm, "updateQueue dst NOT updated, qdbt " + qdbt + " < " +
                             "dbts: " + dbts + ", qstatstr: " + qstatstr) }
    }

    //Seems wasteful to update the notice text every few seconds, but if
    //system may ignore a notice text change due to sleep or whatever.
    fun verifyNotice() {
        var ntxt = defaultNoticeText
        if(playpath == "") {
            ntxt = "Playback stopped" }
        else if(dst != "") {
            var dso = JSONObject(dst)
            if(dso.has("npsi")) {
                var song = dso.getJSONObject("npsi")
                ntxt = song.getString("ti") + " - " + song.getString("ar") } }
        val nmgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nmgr.notify(svcNoticeId, makeFgSvcN(ntxt))
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
        Log.d(lognm, "DiggerAudioService onCreate " + isostamp())
        pbstate = "init"
        dst = ""
        failmsg = ""
        playpath = ""
        //init media session to react to android media controls
        val context = getApplicationContext()
        val tag = "DiggerAudioServiceSession"  //arbitrary but identifiable tag
        val mscn = ComponentName(context,
                                 "com.diggerhub.digger.DiggerAudioService")
        mse = MediaSessionCompat(context, tag, mscn, null)
        mse.setCallback(mscb)
        mse.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                     MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
        mse.isActive = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, stid: Int): Int {
        //intent.action is declared in manifest and used by startService
        if(intent != null) {
            val pathuri = intent.data!!  //force Uri? to Uri
            Log.d(lognm, "onStartCommand pathuri: " + pathuri)
            dasPlaySong(pathuri) }
        else {  //null, service restarted after having been killed
            Log.d(lognm, "onStartCommand null intent (restart)") }
        startForeground(svcNoticeId, makeFgSvcN(defaultNoticeText))
        return Service.START_STICKY  //please restart service after killing it
    }

    override fun onDestroy() {
        super.onDestroy()
        mse.release()
        mp?.release()
        mp = null
    }

    //MediaPlayer.OnPreparedListener interface
    override fun onPrepared(mediaPlayer: MediaPlayer) {
        mp?.start()  //question mark is from doc example. Probably right.
    }

    //MediaPlayer.OnErrorListener interface
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
        Log.e(lognm, "onError: $stat, $err")
        return false  //triggers call to OnCompletionListener
    }

    //MediaPlayer.OnCompletionListener interface
    //containing service keeps binding and state vars
    override fun onCompletion(ignore: MediaPlayer) {
        val now = System.currentTimeMillis()
        if(!dst.isEmpty()) {  //have state info, start autoplay
            Log.d(lognm, "onCompletion autoplay " + isostamp())
            val path = getNextSongPathFromState()
            if(!path.isEmpty()) {  //have next song to play
                noteSongPlayed(path)
                dasPlaySong(Uri.fromFile(File(path))) }
            else {
                Log.i(lognm, "No path for next song, ending.")
                pbstate = "ended" } }
        else {
            Log.i(lognm, "No deck state, ending.")
            pbstate = "ended" }
        verifyNotice()
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
        val lognm = "DiggerHub"
        val dhup = "https://diggerhub.com/api"
        var code = 503  //Service Unavailable
        var res = "Connection failed"
        try {
            Log.d(lognm, "$qname $reqnum $verb /api$endpoint $data")
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
                        Log.e(lognm, "Connection errstream read error", rx)
                        res = responseMessage } } }
        } catch(e: Exception) {
            Log.e(lognm, "Connection call error", e)
        }
        res = res.replace("\\", "\\\\")  //escape contained backslashes
        res = res.replace("\"", "\\\"")  //escape contained quotes
        val cb = "app.svc.hubReqRes(\"$qname\",$reqnum,$code,\"$res\")"
        Log.d(lognm, cb)
        context.runOnUiThread(Runnable() { context.djs(cb) })
    }
}
