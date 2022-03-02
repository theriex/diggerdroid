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
                if(!song.ar) {  //artist required for hub sync
                    mgrs.sg.setArtistFromPath(song); } }); },
        mediaReadComplete: function (err) {
            var dbo; var dais;
            if(err) {
                return jt.out(dbstatdiv, "Music read failed: " + err); }
            jt.out(dbstatdiv, "Fetching audio summary...");
            dais = Android.getAudioItemSummary();
            return jt.log(dais);
            jt.out(dbstatdiv, "Parsing audio summary...");
            dais = JSON.parse(dais);
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
        getDatabase: function () { return dbo; },
        songs: function () { return dbo.songs; },
        fetchSongs: function (contf, ignore /*errf*/) {  //let init proc finish
            setTimeout(function () { contf(dbo.songs); }, 200); },
        writeSongs: function () {
            Android.writeDigDat(JSON.stringify(dbo)); },
        loadInitialData: function () {
            try {
                config = JSON.parse(Android.readConfig() || "{}");
                dbo = JSON.parse(Android.readDigDat() || "{}"); }
            catch(e) {
                return jt.err("Initial data load failed: " + e); }
            config = mgrs.usr.verifyConfig(config);
            dbo = mgrs.sg.verifyDatabase(dbo);
            //background fails to load. Set explicitely so things are visible:
            const cssbg = "url('" + app.dr("/img/panelsbg.png") + "')";
            jt.byId("contentdiv").style.backgroundImage = cssbg;
            //let rest of app know data is ready, then check the library:
            const uims = ["top",      //display login name
                          "filter"];  //show settings, app.deck.update
            uims.forEach(function (uim) { app[uim].initialDataLoaded(); });
            if(!dbo.scanned) {
                setTimeout(mgrs.sg.loadLibrary, 50); } }
    };  //end mgrs.loc returned functions
    }());


    //general manager is main interface for app logic
    mgrs.gen = (function () {
    return {
        getHostType: function () { return "loc"; },  //not running on web
        initialize: function () { setTimeout(mgrs.loc.loadInitialData, 200); }
    };  //end mgrs.gen returned functions
    }());


return {
    init: function () { mgrs.gen.initialize(); },
    getHostType: function () { return mgrs.gen.getHostType(); },
    songs: function () { return mgrs.loc.songs(); },
    fetchSongs: function (cf, ef) { mgrs.loc.fetchSongs(cf, ef); },
    fetchAlbum: function (s, cf, ef) { mgrs.gen.fetchAlbum(s, cf, ef); },
    updateSong: function (song, contf) { mgrs.gen.updateSong(song, contf); },
    authdata: function (obj) { return mgrs.gen.authdata(obj); },
    mediaReadComplete: function (err) { mgrs.sg.mediaReadComplete(err); },
    dispatch: function (mgrname, fname, ...args) {
        return mgrs[mgrname][fname].apply(app.svc, args); }
};  //end of returned functions
}());
