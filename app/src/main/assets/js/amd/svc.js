/*global app, jt, Android, console */
/*jslint browser, white, long, unordered */

//Server communications for Android platform
app.svc = (function () {
    "use strict";

    var mgrs = {};  //general container for managers

    //Media Playback manager handles transport and playback calls.
    mgrs.mp = (function () {
        const srd = {  //status request data
            qmode:"statonly",  //"updnpqsi" if sending new playback queue
            npsi:null,         //now playing song info (ti/ar/path)
            qsi:null};         //queued songs info (remaining song info array)
        var rqn = 0;
        var cq = [];
        const maxRetry = 3;
        function processNextCommand () {
            if(!cq || !cq.length) { return; }  //queue previously cleared
            //result delivered in notePlaybackStatus callback
            Android.serviceInteraction(cq[0].cmd, cq[0].param, cq[0].cc); }
        function commandCompleted (compstat) {
            const processed = cq.shift();
            const logpre = "svc.mp.commandCompleted " + compstat + " ";
            if(processed) {
                jt.log(logpre + processed.cc + ": " + processed.cmd); }
            else {
                jt.log(logpre + "empty command queue"); }
            if(cq.length) {
                processNextCommand(); } }
        function queueCommand (command, parameter) {
            if(cq.length && cq[cq.length - 1].cmd === command) {
                cq[cq.length - 1].param = parameter || "";
                return jt.log("svc.mp.queueCommand " + command +
                              " already queued for processing."); }
            rqn += 1;
            jt.log("svc.mp.queueCommand " + rqn + ": " + command);
            cq.push({cmd:command, param:parameter || "", cc:rqn,
                     retries:maxRetry});
            if(cq.length === 1) {  //no other ongoing processing
                processNextCommand(); } }
        function ssd (song) {  //simplified song details
            return {ti:song.ti, ar:song.ar, path:song.path}; }
        function platRequestPlaybackStatus () {
            if(srd.qmode === "updnpqsi") {  //avoid log clutter
                jt.log("platRequestPlaybackStatus qmode:" + srd.qmode +
                       " np:" + (srd.npsi? srd.npsi.path : "-") +
                       " qsi.length:" + (srd.qsi? srd.qsi.length : "-")); }
            queueCommand("status", JSON.stringify(srd)); }
        function playAndSendQueue () {
            jt.log("playAndSendQueue " + srd.npsi.path);
            try {
                const np = app.player.nowPlayingSong();
                if(!np || np.path !== srd.npsi.path) {
                    Android.playSong(srd.npsi.path); } //rest sent w/status
            } catch(e) {  //shouldn't fail, log if it happens
                jt.log("Android.playSong exception: " + e); }
            setTimeout(function () {  //let play call go, then tick
                //use the main messaging utility for call sequencing.
                app.player.dispatch("uiu", "requestPlaybackStatus", "mp.play",
                    function (status) {
                        //leave npsi/qsi/qmode values until "queueset" received
                        jt.log("playAndSendQueue status return " +
                               JSON.stringify(status)); }); }, 50); }
        function platPlaySongQueue (pwsid, sq) {
            srd.qmode = "updnpqsi";
            srd.npsi = ssd(sq[0]);
            srd.qsi = sq.slice(1).map((s) => ssd(s));
            jt.log("svc.mp.playSongQueue " + pwsid + " " + srd.npsi.path +
                   " srd.qsi.length: " + srd.qsi.length +
                   ", srd.qmode: " + srd.qmode);
            cq = [];  //clear all previous pending transport/status requests
            const song = app.pdat.songsDict()[srd.npsi.path];
            song.lp = new Date().toISOString();
            song.pc = song.pc || 0;
            song.pc += 1;
            song.pd = "played";
            //play first, then write digdat, otherwise digdat listeners will
            //be reacting to non-existent ongoing playback.
            playAndSendQueue();
            app.pdat.writeDigDat(pwsid); }
    return {
        //player.plui pbco interface functions:
        requestPlaybackStatus: platRequestPlaybackStatus,
        playSongQueue: platPlaySongQueue,
        pause: function () { queueCommand("pause"); },
        resume: function () { queueCommand("resume"); },
        seek: function (ms) { queueCommand("seek", String(ms)); },
        //Android callback
        notePlaybackStatus: function (status) {
            if(status.state.startsWith("failed")) {
                status.errmsg = status.state;
                status.state = ""; }
            if(status.qmode === "queueset") {
                if(srd.qmode === "updnpqsi") {  //avoid log clutter
                    jt.log("svc.mp.notePlaybackStatus qmode->statonly"); }
                srd.qmode = "statonly";
                srd.npsi = null;
                srd.qsi = null; }
            if(status.path) {
                status.path = jt.dec(status.path);    //undo URI encode
                status.path = jt.dec(status.path);    //undo File encode
                status.path = status.path.slice(7); } //remove "file://" prefix
            app.player.dispatch("uiu", "receivePlaybackStatus", status);
            commandCompleted("finished"); },
        //player initialization
        beginTransportInterface: function () {
            app.player.dispatch("uiu", "requestPlaybackStatus", "mp.start",
                function (status) {
                    if(status.path) {  //app init found this song playing
                        const song = app.pdat.songsDict()[status.path];
                        app.player.notifySongChanged(song, status.state); }
                    else {  //not already playing
                        jt.log("mp.beginTransport no playing song"); } }); }
    };  //end mgrs.mp returned functions
    }());


    //Copy export manager handles playlist creation.  No file copying.
    mgrs.cpx = (function () {
    return {
        exportSongs: function (/*dat, statusfunc, contfunc, errfunc*/) {
            jt.log("svc.cpx.exportSongs not supported."); }
    };  //end mgrs.cpx returned functions
    }());


    //song database processing
    mgrs.sg = (function () {
        var dls = null;  //data load state
        function parseAudioSummary (dais) {
            dais = JSON.parse(dais);
            dais = dais.filter((d) =>  //title and playback path required
                d.title && (d.data || (d.relpath && d.dispname)));
            dais.forEach(function (dai) {  //fill fields, make playback path
                dai.artist = dai.artist || "Unknown";
                dai.album = dai.album || "Singles";
                dai.genre = dai.genre || "";
                if(dai.data) {  //prefer full path if available, need file URI.
                    dai.path = dai.data; }
                else {  //better than nothing, but file path unrecoverable
                    dai.path = dai.relpath + dai.dispname; } });
            return dais; }
        function setArtistFromPath (song) {
            const pes = song.path.split("/");
            song.ti = pes[pes.length - 1];
            if(pes.length >= 3) {
                song.ar = pes[pes.length - 3];
                song.ab = pes[pes.length - 2]; }
            else if(pes.length >= 2) {
                song.ar = pes[pes.length - 2]; } }
        function updateDataFromDroidAudio (dbo, dais) {
            dais.forEach(function (dai) {
                var song = dbo.songs[dai.path];
                if(!song) {
                    dbo.songs[dai.path] = {};
                    song = dbo.songs[dai.path]; }
                song.path = dai.path;
                song.fq = song.fq || "N";
                if(song.fq.startsWith("D")) {
                    song.fq = song.fq.slice(1); }
                song.ti = dai.title;
                song.ar = dai.artist;
                song.ab = dai.album;
                song.mddn = dai.discnum;
                song.mdtn = dai.track;
                song.genrejson = JSON.stringify(dai.genre);
                app.top.dispatch("dbc", "verifySong", song);
                if(!song.ar) {  //artist required for hub sync
                    setArtistFromPath(song); } }); }
    return {
        mediaReadComplete: function (err) {
            if(err) { return dls.errf(500, err); }
            dls.dais = Android.getAudioItemSummary();
            dls.dais = parseAudioSummary(dls.dais);
            Object.values(dls.dbo.songs).forEach(function (s) {
                s.fq = s.fq || "N";
                if(!s.fq.startsWith("D")) {
                    s.fq = "D" + s.fq; } });  //mark all songs deleted
            dls.dbo.scanned = new Date().toISOString();
            dls.dbo.songcount = dls.dais.length;
            updateDataFromDroidAudio(dls.dbo, dls.dais);  //set ti/ar/ab etc
            mgrs.sg.writeDigDat(dls.dbo, null, dls.contf); },
        readDigDat: function (contfunc, errfunc) {
            dls = {dbo:{}, contf:contfunc, errf:errfunc};
            try {
                const ret = Android.readDigDat();
                if(ret) {
                    dls.dbo = JSON.parse(ret); }
            } catch(e) {
                jt.log("svc.sg.readDigDat Android read error " + e); }
            dls.dbo.version = Android.getAppVersion();
            dls.dbo.songs = dls.dbo.songs || {};
            Android.requestMediaRead(); },  //calls back to mediaReadComplete
        writeDigDat: function (dbo, ignore/*optobj*/, contf/*, errf*/) {
            Android.writeDigDat(JSON.stringify(dbo, null, 2));
            setTimeout(function () { contf(dbo); }, 50); }
    };  //end mgrs.sg returned functions
    }());


    //Local manager handles local environment interaction
    mgrs.loc = (function () {
    return {
        readConfig: function (contf/*, errf*/) {
            var config = {};  //default empty config
            try {
                config = Android.readConfig() || "{}";
                config = JSON.parse(config);
            } catch(e) {
                jt.log("svc.loc.readConfig error " + e); }
            contf(config); },
        writeConfig: function (config, ignore/*optobj*/, contf/*, errf*/) {
            Android.writeConfig(JSON.stringify(config, null, 2));
            setTimeout(function () { contf(config); }, 50); }
    };  //end mgrs.loc returned functions
    }());


    //general manager is main interface for app logic
    mgrs.gen = (function () {
        const platconf = {
            hdm: "loc",   //host data manager is local
            musicPath: "fixed",  //can't change where music files are
            dbPath: "fixed",  //rating info is only kept in app files for now
            urlOpenSupp: false,  //opening a tab breaks webview
            defaultCollectionStyle: "",   //not permanentCollection
            audsrc: "Android",
            versioncode: Android.getVersionCode() };
    return {
        initialize: function () {
            app.boot.addApresModulesInitTask("initPLUI", function () {
                app.player.dispatch("plui", "initInterface", mgrs.mp); });
            app.pdat.addApresDataNotificationTask("startPLUI", function () {
                mgrs.mp.beginTransportInterface(); });
            app.pdat.svcModuleInitialized(); },
        plat: function (key) { return platconf[key]; },
        okToPlay: function (song) {
            //m4a files play as video, but MediaPlayer just crashes
            if(song.path.toLowerCase().endsWith(".m4a")) {
                //jt.log("filtering out " + song.path);
                return false; }
            return song; },
        passthroughHubCall: function (qname, reqnum, endpoint, verb, dat) {
            Android.hubRequest(qname, reqnum, endpoint, verb, dat); },
        hubResponse: function (qname, reqnum, code, det) {
            app.top.dispatch("hcq", "hubResponse", qname, reqnum, code, det); },
        docContent: function (docurl, contf) {
            var fn = jt.dec(docurl);
            var sidx = fn.lastIndexOf("/");
            if(sidx >= 0) {
                fn = fn.slice(sidx + 1); }
            const text = Android.getAssetContent("docs/" + fn);
            contf(text); },
        copyToClipboard: function (txt, contf, errf) {
            if(Android.copyToClipboard(txt)) {
                return contf(); }
            errf(); },
        tlasupp: function (act) {
            const unsupp = {
                "updversionnote":"Play Store updates after server",
                "ignorefldrsbutton":"No music file folders on Android",
                "readfilesbutton":"All media queried at app startup"};
            return (!act.id || !unsupp[act.id]); }
    };  //end mgrs.gen returned functions
    }());


return {
    init: function () { mgrs.gen.initialize(); },
    plat: function (key) { return mgrs.gen.plat(key); },
    readConfig: mgrs.loc.readConfig,
    readDigDat: mgrs.sg.readDigDat,
    writeConfig: mgrs.loc.writeConfig,
    writeDigDat: mgrs.sg.writeDigDat,
    playSongQueue: mgrs.mp.playSongQueue,
    requestPlaybackStatus: mgrs.mp.requestPlaybackStatus,
    notePlaybackStatus: mgrs.mp.notePlaybackStatus,   //Android callback
    passthroughHubCall: mgrs.gen.passthroughHubCall,
    hubReqRes:mgrs.gen.hubResponse,                   //Android callback
    copyToClipboard: mgrs.gen.copyToClipboard,
    okToPlay: mgrs.gen.okToPlay,
    mediaReadComplete: mgrs.sg.mediaReadComplete,     //Android callback
    docContent: function (du, cf) { mgrs.gen.docContent(du, cf); },
    topLibActionSupported: function (a) { return mgrs.gen.tlasupp(a); },
    extensionInterface: function (/*name*/) { return null; }
};  //end of returned functions
}());
