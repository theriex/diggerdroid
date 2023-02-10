/*global app, jt, Android, console */
/*jslint browser, white, long, unordered */

//Server communications for Android platform
app.svc = (function () {
    "use strict";

    var mgrs = {};  //general container for managers


    //Media Playback manager handles transport and playback calls
    mgrs.mp = (function () {
        var rqn = 0;
        var cq = [];
        const stateQueueMax = 200;  //generally 6+ hrs of music
        const mperrstat = "MediaPlayer error";
        function processNextCommand () {
            if(!cq || !cq.length) { return; }  //queue previously cleared
            //result delivered in notePlaybackStatus callback
            Android.serviceInteraction(cq[0].cmd, cq[0].param, cq[0].cc); }
        function queueCommand (command, parameter) {
            if(cq.length && cq[cq.length - 1].cmd === command) {
                cq[cq.length - 1].param = parameter || "";
                return jt.log("svc.mp.queueCommand " + command +
                              " already queued for processing."); }
            rqn += 1;
            jt.log("svc.mp.queueCommand " + rqn + ": " + command);
            cq.push({cmd:command, param:parameter || "", cc:rqn});
            if(cq.length === 1) {  //no other ongoing processing
                processNextCommand(); } }
    return {
        getStateQueueMax: function () { return stateQueueMax; },
        requestStatusUpdate: function (/*contf*/) {
            var qm = stateQueueMax;
            qm = app.player.dispatch("slp", "limitToSleepQueueMax", qm);
            const dst = app.deck.getState(qm);  //songs currently on deck
            jt.log("requestStatusUpdate dst.det.length: " + dst.det.length);
            queueCommand("status", JSON.stringify(dst)); },
        pause: function () { queueCommand("pause"); },
        resume: function () { queueCommand("resume"); },
        seek: function (ms) { queueCommand("seek", String(ms)); },
        playSong: function (path) {
            jt.log("svc.mp.playSong: " + path);
            cq = [];  //clear all previous pending transport/status requests
            try {
                Android.playSong(path);
            } catch(e) {
                jt.log("playSong exception: " + e);
                mgrs.mp.playerFailure("Service crashed"); } },
        playerFailure: function (err) {
            jt.log("mp.playerFailure: " + err);
            app.player.dispatch("mob", "handlePlayFailure",
                                mperrstat, err); },
        notePlaybackStatus: function (stat) {
            jt.log("svc.mp.notePlaybackStatus stat: " + JSON.stringify(stat));
            if(!cq.length) {  //no command in queue to connect this result to
                return jt.log("svc.mp.notePlaybackStatus cq empty"); }
            if(!stat.state) {  //indeterminate result, retry
                setTimeout(processNextCommand, 200);
                return jt.log("svc.mp.notePlaybackStatus retrying..."); }
            if(stat.state.startsWith("failed")) {
                jt.log("notePlaybackStatus server " + stat.state);
                app.player.dispatch("mob", "handlePlayFailure",
                                    mperrstat, stat.state);
                return; }
            if(stat.path) {
                stat.path = jt.dec(stat.path);    //undo URI encode
                stat.path = jt.dec(stat.path);    //undo File encode
                stat.path = stat.path.slice(7); } //remove "file://" prefix
            app.player.dispatch("mob", "notePlaybackStatus", stat);
            stat.song = app.player.song();
            Android.noteState("player", JSON.stringify(stat));
            mgrs.loc.noteUpdatedState("deck");
            const processed = cq.shift();
            jt.log("svc.mp.notePlaybackStatus finished " + processed.cc +
                   ": " + processed.cmd);
            if(cq.length) {
                processNextCommand(); } }
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
        var dbstatdiv = "topdlgdiv";
        var apresloadcmd = "";
    return {
        setArtistFromPath: function (song) {
            const pes = song.path.split("/");
            song.ti = pes[pes.length - 1];
            if(pes.length >= 3) {
                song.ar = pes[pes.length - 3];
                song.ab = pes[pes.length - 2]; }
            else if(pes.length >= 2) {
                song.ar = pes[pes.length - 2]; } },
        updateDataFromDroidAudio: function (dbo, dais) {
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
                app.top.dispatch("dbc", "verifySong", song);
                if(!song.ar) {  //artist required for hub sync
                    mgrs.sg.setArtistFromPath(song); } }); },
        parseAudioSummary: function (dais) {
            dais = JSON.parse(dais);
            dais = dais.filter((d) =>  //title and playback path required
                d.title && (d.data || (d.relpath && d.dispname)));
            dais.forEach(function (dai) {  //fill fields, make playback path
                dai.artist = dai.artist || "Unknown";
                dai.album = dai.album || "Singles";
                if(dai.data) {  //prefer full path if available, need file URI.
                    dai.path = dai.data; }
                else {  //better than nothing, but file path unrecoverable
                    dai.path = dai.relpath + dai.dispname; } });
            return dais; },
        mediaReadComplete: function (err) {
            var dbo; var dais;
            if(err) {
                return jt.out(dbstatdiv, "Music read failed: " + err); }
            jt.out(dbstatdiv, "Fetching audio summary...");
            dais = Android.getAudioItemSummary();
            jt.out(dbstatdiv, "Parsing audio summary...");
            dais = mgrs.sg.parseAudioSummary(dais);
            jt.out(dbstatdiv, "Merging Digger data...");
            dbo = mgrs.loc.getDatabase();
            Object.values(dbo.songs).forEach(function (s) {  //mark all deleted
                s.fq = s.fq || "N";
                if(!s.fq.startsWith("D")) {
                    s.fq = "D" + s.fq; } });
            dbo.songcount = dais.length;
            mgrs.sg.updateDataFromDroidAudio(dbo, dais);
            jt.out("countspan", String(dbo.songcount) + "&nbsp;songs");
            mgrs.loc.writeSongs();
            jt.out(dbstatdiv, "");
            app.top.markIgnoreSongs();
            app.top.rebuildKeywords();
            app.deck.update("rebuildSongData");
            if(apresloadcmd === "rebuild") {
                app.player.next(); } },
        loadLibrary: function (procdivid, apresload) {
            dbstatdiv = procdivid || "topdlgdiv";
            apresloadcmd = apresload || "";
            jt.out(dbstatdiv, "Reading music...");
            Android.requestMediaRead(); },
        verifyDatabase: function (dbo) {
            var stat = app.top.dispatch("dbc", "verifyDatabase", dbo);
            if(stat.verified) { return dbo; }
            jt.log("svc.db.verifyDatabase re-initializing dbo, received " +
                   JSON.stringify(stat));
            dbo = {version:Android.getAppVersion(),
                   scanned:"",  //ISO latest walk of song files
                   songcount:0,
                   //songs are indexed by relative path off of musicPath e.g.
                   //"artistFolder/albumFolder/disc#?/songFile"
                   songs:{}};
            return dbo; }
    };  //end mgrs.sg returned functions
    }());


    //hub communications manager handles async hub requests/callbacks
    mgrs.hc = (function () {
        var rqs = {};  //request queues by queue name
        var reqnum = 1;
        function startRequest (entry) {
            entry.dat = entry.dat || "";
            jt.log("mgrs.hc " + JSON.stringify(entry));
            Android.hubRequest(entry.qn, entry.rn,
                               entry.ep, entry.v, entry.dat);
        }
    return {
        queueRequest: function (qname, endpoint, verb, data, contf, errf) {
            contf = contf || function (res) {
                jt.log("svc.hc.queueRequest contf " + JSON.stringify(res)); };
            errf = errf || function (code, text) {
                jt.log("svc.hc.queueRequest errf " + code + ": " + text); };
            const entry = {qn:qname, ep:endpoint, v:verb, dat:data,
                           cf:contf, ef:errf, rn:reqnum};
            reqnum += 1;
            if(rqs[qname]) {  //existing request(s) pending
                rqs[qname].push(entry); }  //process in turn
            else {  //queueing a new request
                rqs[qname] = [entry];
                startRequest(entry); } },
        hubResponse: function (qname, reqnum, code, det) {
            if(reqnum !== rqs[qname][0].rn) {
                return jt.log("ignoring bad request return " + qname + " " +
                              reqnum + " (" + rqs[qname][0].rn + " expected" +
                              ") code: " + code + ", det: " + det); }
            const entry = rqs[qname].shift();
            if(!rqs[qname].length) { delete rqs[qname]; }
            if(code === 200) {
                entry.cf(JSON.parse(det)); }
            else {
                entry.ef(code, det); } }
    };  //end mgrs.hc returned functions
    }());


    //Local manager handles local environment interaction
    mgrs.loc = (function () {
        var config = null;
        var dbo = null;
        function setAndWriteConfig(cfg) {
            config = cfg;
            Android.writeConfig(JSON.stringify(config, null, 2)); }
    return {
        getConfig: function () { return config; },
        getDigDat: function () { return dbo; },
        songs: function () { return mgrs.loc.getDigDat().songs; },
        writeConfig: function (cfg, contf/*, errf*/) {
            setAndWriteConfig(cfg);
            setTimeout(function () { contf(config); }, 50); },
        noteUpdatedAcctsInfo: function(acctsinfo) {
            config.acctsinfo = acctsinfo; },
        updateAccount: function (acctsinfo, contf/*, errf*/) {
            config.acctsinfo = acctsinfo;
            setAndWriteConfig(config);
            setTimeout(function () { contf(config.acctsinfo); }, 50); },
        getDatabase: function () { return dbo; },
        loadDigDat: function (cbf) {
            try {
                dbo = JSON.parse(Android.readDigDat() || "{}");
                dbo = mgrs.sg.verifyDatabase(dbo); }
            catch(e) {
                return jt.err("loadDigDat failed: " + e); }
            cbf(dbo); },
        fetchSongs: function (contf/*, errf*/) {  //call stack as if web call
            setTimeout(function () { contf(dbo.songs); }, 50); },
        fetchAlbum: function (song, contf/*, errf*/) {
            var lsi = song.path.lastIndexOf("/");  //last separator index
            const pp = song.path.slice(0, lsi + 1);  //path prefix
            const abs = Object.values(dbo.songs)  //album songs
            //simple ab match won't work (e.g. "Greatest Hits").  ab + ar fails
            //if the artist name varies (e.g. "main artist featuring whoever".
                .filter((s) => s.path.startsWith(pp))
                .sort(function (a, b) {  //assuming filename start with track#
                    return a.path.localeCompare(b.path); });
            contf(song, abs); },
        writeSongs: function () {
            var stat = app.top.dispatch("dbc", "verifyDatabase", dbo);
            if(!stat.verified) {
                return jt.err("Not writing bad data " + JSON.stringify(stat)); }
            Android.writeDigDat(JSON.stringify(dbo, null, 2)); },
        updateSong: function (song, contf, ignore /*errf*/) {
            app.copyUpdatedSongData(song, dbo.songs[song.path]);
            mgrs.loc.writeSongs();
            jt.out("modindspan", "");  //turn off indicator light
            app.top.dispatch("srs", "syncToHub");  //sched sync
            if(contf) {
                contf(dbo.songs[song.path]); } },
        noteUpdatedState: function (label) {
            if(label === "deck") {
                Android.noteState("deck",
                                  JSON.stringify(app.deck.getState(
                                      mgrs.mp.getStateQueueMax()))); } },
        restoreState: function () {
            var state = Android.getRestoreState("player");
            if(state) {
                jt.log("restoreState player: " + state);
                app.player.setState(JSON.parse(state)); }
            state = Android.getRestoreState("deck");
            if(state) {
                jt.log("restoreState deck: " + state);
                app.deck.setState(JSON.parse(state)); } },
        loadInitialData: function () {
            //background fails to load. Set explicitely so things are visible:
            const cssbg = "url('" + app.dr("/img/panelsbg.png") + "')";
            jt.byId("contentdiv").style.backgroundImage = cssbg;
            mgrs.loc.restoreState();  //remember previous state before reload
            try {
                config = JSON.parse(Android.readConfig() || "{}");
                dbo = JSON.parse(Android.readDigDat() || "{}"); }
            catch(e) {
                return jt.err("Initial data load failed: " + e); }
            config = config || {};  //default account set up in top.js
            dbo = mgrs.sg.verifyDatabase(dbo);
            //let rest of app know data is ready, then check the library:
            const startdata = {"config":config, songdata:dbo};
            const uims = ["top",      //display login name
                          "filter"];  //show settings, app.deck.update
            uims.forEach(function (uim) {
                app[uim].initialDataLoaded(startdata); });
            if(!dbo.scanned) {
                setTimeout(mgrs.sg.loadLibrary, 50); } },
        loadLibrary: function (procdivid) {
            mgrs.sg.loadLibrary(procdivid); },
        procSyncData: function (res) {  //hub.js processReceivedSyncData
            app.player.logCurrentlyPlaying("svc.loc.procSyncData");
            const updacc = res[0];
            updacc.diggerVersion = Android.getAppVersion();
            app.deck.dispatch("hsu", "noteSynchronizedAccount", updacc);
            app.deck.dispatch("hsu", "updateSynchronizedSongs", res.slice(1));
            return res; },
        hubSyncDat: function (data, contf, errf) {
            mgrs.hc.queueRequest("hubSyncDat", "/hubsync", "POST", data,
                                 function (res) {
                                     res = mgrs.loc.procSyncData(res);
                                     contf(res); }, errf); },
        noteUpdatedSongData: function (updsong) {
            //on Android the local database has already been updated, and
            //local memory is up to date.
            return dbo.songs[updsong.path]; },
        makeHubAcctCall: function (verb, endpoint, data, contf, errf) {
            mgrs.hc.queueRequest(endpoint, "/" + endpoint, verb, data,
                                 contf, errf); }
    };  //end mgrs.loc returned functions
    }());


    //general manager is main interface for app logic
    mgrs.gen = (function () {
        var platconf = {
            hdm: "loc",   //host data manager is local
            musicPath: "fixed",  //can't change where music files are
            dbPath: "fixed",  //rating info is only kept in app files for now
            audsrc: "Android",
            versioncode: Android.getVersionCode() };
    return {
        plat: function (key) { return platconf[key]; },
        noteUpdatedSongData: function (song) {
            return mgrs.loc.noteUpdatedSongData(song); },
        updateMultipleSongs: function (/*updss, contf, errf*/) {
            jt.err("svc.gen.updateMultipleSongs is web only"); },
        initialize: function () {  //don't block init of rest of modules
            setTimeout(mgrs.loc.loadInitialData, 50); },
        docContent: function (docurl, contf) {
            var fn = jt.dec(docurl);
            var sidx = fn.lastIndexOf("/");
            if(sidx >= 0) {
                fn = fn.slice(sidx + 1); }
            const text = Android.getAssetContent("docs/" + fn);
            contf(text); },
        makeHubAcctCall: function (verb, endpoint, data, contf, errf) {
            mgrs.loc.makeHubAcctCall(verb, endpoint, data, contf, errf); },
        writeConfig: function (cfg, contf, errf) {
            mgrs.loc.writeConfig(cfg, contf, errf); },
        fanGroupAction: function (data, contf, errf) {
            mgrs.hc.queueRequest("fangrpact", "/fangrpact", "POST", data,
                                 //caller writes updated account data
                                 contf, errf); },
        fanCollab: function (data, contf, errf) {
            mgrs.hc.queueRequest("fancollab", "/fancollab", "POST", data,
                                 function (res) {
                                     res = mgrs.loc.procSyncData(res);
                                     contf(res); }, errf); },
        fanMessage: function (data, contf, errf) {
            mgrs.hc.queueRequest("fanmsg", "/fanmsg", "POST", data,
                                 contf, errf); },
        copyToClipboard: function (txt, contf, errf) {
            if(Android.copyToClipboard(txt)) {
                return contf(); }
            errf(); }
    };  //end mgrs.gen returned functions
    }());


return {
    init: function () { mgrs.gen.initialize(); },
    plat: function (key) { return mgrs.gen.plat(key); },
    loadDigDat: function (cbf) { mgrs.loc.loadDigDat(cbf); },
    songs: function () { return mgrs.loc.songs; },
    fetchSongs: function (cf, ef) { mgrs.loc.fetchSongs(cf, ef); },
    fetchAlbum: function (s, cf, ef) { mgrs.loc.fetchAlbum(s, cf, ef); },
    updateSong: function (song, cf, ef) { mgrs.loc.updateSong(song, cf, ef); },
    noteUpdatedState: function (label) { mgrs.loc.noteUpdatedState(label); },
    mediaReadComplete: function (err) { mgrs.sg.mediaReadComplete(err); },
    playerFailure: function (err) { mgrs.mp.playerFailure(err); },
    notePlaybackStatus: function (stat) { mgrs.mp.notePlaybackStatus(stat); },
    hubReqRes: function (q, r, c, d) { mgrs.hc.hubResponse(q, r, c, d); },
    urlOpenSupp: function () { return false; }, //links break webview
    docContent: function (du, cf) { mgrs.gen.docContent(du, cf); },
    writeConfig: function (cfg, cf, ef) { mgrs.gen.writeConfig(cfg, cf, ef); },
    dispatch: function (mgrname, fname, ...args) {
        try {
            return mgrs[mgrname][fname].apply(app.svc, args);
        } catch(e) {
            console.log("top.dispatch: " + mgrname + "." + fname + " " + e +
                        " " + new Error("stack trace").stack);
        } }
};  //end of returned functions
}());
