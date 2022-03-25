/*global app, jt, Android */
/*jslint browser, white, long, unordered */

//Server communications for Android platform
app.svc = (function () {
    "use strict";

    const droidversion = "v1.0.0"; 
    const dfltkeywords = {  //copied from digger/server/dhdefs.js
        Social: {pos: 1, sc: 0, ig: 0, dsc: "Music I might select to play when other people are listening."},
        Personal: {pos: 2, sc: 0, ig: 0, dsc: "Music I might select to play when it's just me listening."},
        Office: {pos: 3, sc: 0, ig: 0, dsc: "Music that you can listen to while you work."},
        Dance: {pos: 4, sc: 0, ig: 0, dsc: "Music you would dance to."},
        Ambient: {pos: 0, sc: 0, ig: 0, dsc: "Music that can be listened to from zero to full attention, transitioning from and to silence."},
        Jazz: {pos: 0, sc: 0, ig: 0, dsc: "However you define it for your collection."},
        Classical: {pos: 0, sc: 0, ig: 0, dsc: "However you define it for your collection."},
        Talk: {pos: 0, sc: 0, ig: 0, dsc: "Spoken word."},
        Solstice: {pos: 0, sc: 0, ig: 0, dsc: "Holiday seasonal."}};

    var mgrs = {};  //general container for managers


    //Media Playback manager handles transport and playback calls
    mgrs.mp = (function () {
        var rqn = 0;
        var cq = [];
        function processNextCommand () {
            //result delivered in notePlaybackStatus callback
            Android.serviceInteraction(cq[0].cmd, cq[0].param, cq[0].cc); }
        function queueCommand (command, parameter) {
            if(cq.length && cq[cq.length - 1].cmd === command) {
                return jt.log("svc.mp.queueCommand " + command +
                              " already queued for processing."); }
            rqn += 1;
            jt.log("svc.mp.queueCommand " + rqn + ": " + command);
            cq.push({cmd:command, param:parameter || "", cc:rqn});
            if(cq.length === 1) {  //no other ongoing processing
                processNextCommand(); } }
    return {
        requestStatusUpdate: function (/*contf*/) { queueCommand("status"); },
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
                                "Player error", err); },
        notePlaybackStatus: function (stat) {
            if(!cq.length) {
                return jt.log("svc.mp.notePlaybackStatus cq empty"); }
            if(!stat.state) {  //indeterminate result, retry
                setTimeout(processNextCommand, 200);
                return jt.log("svc.mp.notePlaybackStatus retrying..."); }
            jt.log("svc.mp.notePlaybackStatus " + JSON.stringify(stat));
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
            jt.log("svc.cpx.exportSongs not implemented yet"); }
    };  //end mgrs.cpx returned functions
    }());


    //user and config processing
    mgrs.usr = (function () {
    return {
        verifyConfig: function (cfg) {
            cfg = cfg || {};
            if(cfg.acctsinfo) { return cfg; }  //already initialized
            //see hub.js verifyDefaultAccount in main Digger project
            cfg.acctsinfo = {currid:"", accts:[]};
            if(!cfg.acctsinfo.accts.find((x) => x.dsId === "101")) {
                const diggerbday = "2019-10-11T00:00:00Z";
                cfg.acctsinfo.accts.push(
                    {dsType:"DigAcc", dsId:"101", firstname:"Digger",
                     created:diggerbday, modified:diggerbday + ";1",
                     email:"support@diggerhub.com", token:"none",
                     kwdefs:dfltkeywords, igfolds:[]}); }
            if(!cfg.acctsinfo.currid) {
                cfg.acctsinfo.currid = "101"; }
            return cfg; }
    };  //end mgrs.usr returned functions
    }());


    //song database processing
    mgrs.sg = (function () {
        var dbstatdiv = "topdlgdiv";
        function verifySongWorkingFields(s) {
            s.el = s.el || 49;
            s.al = s.al || 49;
            s.kws = s.kws || "";
            s.rv = s.rv || 5;
            s.fq = s.fq || "N";
            s.lp = s.lp || "";
            s.nt = s.nt || "";
            s.pc = s.pc || 0;
            s.srcid = s.srcid || "";
            s.srcrat = s.srcrat || ""; }
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
                verifySongWorkingFields(song);
                if(!song.ar) {  //artist required for hub sync
                    mgrs.sg.setArtistFromPath(song); } }); },
        parseAudioSummary: function (dais) {
            dais = JSON.parse(dais);
            dais = dais.filter((d) =>  //title and playback path required
                d.title && (d.data || (d.relpath && d.dispname)));
            dais.forEach(function (dai) {  //fill fields, make playback path
                dai.artist = dai.artist || "Unknown";
                dai.album = dai.album || "Singles";
                if(dai.data) {  //prefer full path if available
                    dai.path = dai.data; }
                else {
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
            app.deck.update("rebuildSongData"); },
        loadLibrary: function (procdivid) {
            dbstatdiv = procdivid || "topdlgdiv";
            jt.out(dbstatdiv, "Reading music...");
            Android.requestMediaRead(); },
        verifyDatabase: function (dbo) {
            if(dbo && dbo.version) { return dbo; }  //already initialized
            dbo = {version:droidversion,
                   scanned:"",  //ISO latest walk of song files
                   songcount:0,
                   //songs are indexed by relative path off of musicPath e.g.
                   //"artistFolder/albumFolder/disc#?/songFile"
                   songs:{}};
            return dbo; }
    };  //end mgrs.db returned functions
    }());


    //Local manager handles local environment interaction
    mgrs.loc = (function () {
        var config = null;
        var dbo = null;
    return {
        getConfig: function () { return config; },
        writeConfig: function () {
            jt.err("No user configurable paths on Android platform."); },
        noteUpdatedAcctsInfo: function(acctsinfo) {
            config.acctsinfo = acctsinfo; },
        updateAccount: function (acctsinfo, contf/*, errf*/) {
            config.acctsinfo = acctsinfo;
            Android.writeConfig(JSON.stringify(config));
            setTimeout(function () { contf(config.acctsinfo); }, 50); },
        getDatabase: function () { return dbo; },
        songs: function () { return dbo.songs; },
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
            Android.writeDigDat(JSON.stringify(dbo)); },
        updateSong: function (song, contf) {
            mgrs.gen.copyUpdatedSongData(song, dbo.songs[song.path]);
            mgrs.loc.writeSongs();
            jt.out("modindspan", "");  //turn off indicator light
            app.top.dispatch("a2h", "syncToHub");  //sched sync
            if(contf) {
                contf(dbo.songs[song.path]); } },
        noteUpdatedState: function (label) {
            if(label === "deck") {
                Android.noteState("deck",
                                  JSON.stringify(app.deck.getState())); } },
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
            mgrs.loc.restoreState();
            try {
                config = JSON.parse(Android.readConfig() || "{}");
                dbo = JSON.parse(Android.readDigDat() || "{}"); }
            catch(e) {
                return jt.err("Initial data load failed: " + e); }
            config = mgrs.usr.verifyConfig(config);
            dbo = mgrs.sg.verifyDatabase(dbo);
            jt.log("dbs " + Object.keys(dbo.songs));
            //let rest of app know data is ready, then check the library:
            const uims = ["top",      //display login name
                          "filter"];  //show settings, app.deck.update
            uims.forEach(function (uim) { app[uim].initialDataLoaded(); });
            if(!dbo.scanned) {
                setTimeout(mgrs.sg.loadLibrary, 50); } },
        hubSyncDat: function (/*data, contf, errf*/) {
            jt.log("svc.loc.hubSyncDat not implemented yet"); },
        noteUpdatedSongData: function (/*updsong*/) {
            jt.log("svc.loc.noteUpdatedSongData not implemented yet"); },
        updateHubAccount: function (/*contf, errf*/) {
            jt.log("svc.loc.updateHubAccount not implemented yet"); },
        signInOrJoin: function (/*endpoint, data, contf, errf*/) {
            jt.log("svc.loc.signInOrJoin not implemented yet"); },
        emailPwdReset: function (/*data, contf, errf*/) {
            jt.log("svc.loc.emailPwdReset not implemented yet"); }
    };  //end mgrs.loc returned functions
    }());


    //general manager is main interface for app logic
    mgrs.gen = (function () {
        var songfields = ["dsType", "batchconv", "aid", "ti", "ar", "ab",
                          "el", "al", "kws", "rv", "fq", "lp", "nt",
                          "dsId", "modified"];
    return {
        getHostType: function () { return "loc"; },  //not running on web..
        getAudioPlatform: function () { return "Android"; },
        addFriend: function (/*mfem, contf, errf*/) {
            jt.log("svc.gen.addFriend not implemented yet"); },
        createFriend: function (/*dat, contf, errf*/) {
            jt.log("svc.gen.createFriend not implemented yet"); },
        friendContributions: function (/*contf, errf*/) {
            jt.log("svc.gen.friendContributions not implemented yet"); },
        clearFriendRatings: function (/*mfid, contf, errf*/) {
            jt.log("svc.gen.clearFriendRatings not implemented yet"); },
        authdata: function (obj) { //return obj post data, with an/at added
            var digacc = app.top.dispatch("gen", "getAccount");
            var authdat = jt.objdata({an:digacc.email, at:digacc.token});
            if(obj) {
                authdat += "&" + jt.objdata(obj); }
            return authdat; },
        copyUpdatedSongData: function (song, updsong) {
            songfields.forEach(function (fld) {
                if(updsong.hasOwnProperty(fld)) {  //don't copy undefined values
                    song[fld] = updsong[fld]; } }); },
        updateMultipleSongs: function (/*updss, contf, errf*/) {
            jt.err("svc.gen.updateMultipleSongs is web only"); },
        initialize: function () {  //don't block init of rest of modules
            setTimeout(mgrs.loc.loadInitialData, 50); }
    };  //end mgrs.gen returned functions
    }());


return {
    init: function () { mgrs.gen.initialize(); },
    getHostType: function () { return mgrs.gen.getHostType(); },
    songs: function () { return mgrs.loc.songs(); },
    fetchSongs: function (cf, ef) { mgrs.loc.fetchSongs(cf, ef); },
    fetchAlbum: function (s, cf, ef) { mgrs.loc.fetchAlbum(s, cf, ef); },
    updateSong: function (song, contf) { mgrs.loc.updateSong(song, contf); },
    authdata: function (obj) { return mgrs.gen.authdata(obj); },
    noteUpdatedState: function (label) { mgrs.loc.noteUpdatedState(label); },
    mediaReadComplete: function (err) { mgrs.sg.mediaReadComplete(err); },
    playerFailure: function (err) { mgrs.mp.playerFailure(err); },
    notePlaybackStatus: function (stat) { mgrs.mp.notePlaybackStatus(stat); },
    dispatch: function (mgrname, fname, ...args) {
        return mgrs[mgrname][fname].apply(app.svc, args); }
};  //end of returned functions
}());
