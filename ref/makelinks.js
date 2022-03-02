/*jslint node, white */

//Link the digger webapp src files into app/src/main/assets
//usage:
//  node makelinks.js
//or
//  node makelinks.js delete
//to get rid of the created links.  The digger project must be available as
//a sibling project off the same parent directory as diggerdroid.

var linker = (function () {
    "use strict";

    var fs = require("fs");
    var ws = {linkdirs:["", "css", "img", "js", "js/amd", "docs"],
              ovr:{"svc.js": "Local version to interface with phone"}};


    function makeWorkingSetRoots () {
        var dn = __dirname;
        ws.lnkr = dn.slice(0, dn.lastIndexOf("/"));
        ws.digr = ws.lnkr.slice(0, ws.lnkr.lastIndexOf("/") + 1) +
            "digger/docroot/";
        ws.lnkr += "/app/src/main/assets/";
    }


    function jslf (obj, method, ...args) {
        return obj[method].apply(obj, args);
    }


    function checkLink (cmd, relpath, fname) {
        var hfp = ws.lnkr + relpath + "/" + fname;
        var dfp = ws.digr + relpath + "/" + fname;
        if(fname.endsWith("~")) { return; }
        if(ws.ovr[fname]) { return; }
        if(!jslf(fs, "existsSync", hfp)) {
            if(cmd === "create") {
                fs.symlink(dfp, hfp, function (err) {
                    if(err) { throw err; }
                    console.log("created " + hfp); }); }
            else {
                console.log("missing " + hfp); } }
        else {
            if(cmd === "delete") {
                fs.unlink(hfp, function (err) {
                    if(err) { throw err; }
                    console.log("removed " + hfp); }); }
            else {
                console.log(" exists " + hfp); } }
    }


    function traverseLinks (cmd) {
        console.log("Command: " + cmd);
        makeWorkingSetRoots();
        console.log("Linking " + ws.digr + " files to " + ws.lnkr);
        ws.linkdirs.forEach(function (relpath) {
            var dir = ws.digr + relpath;
            var options = {encoding:"utf8", withFileTypes:true};
            fs.readdir(dir, options, function (err, dirents) {
                if(err) { throw err; }
                dirents.forEach(function (dirent) {
                    if(dirent.isFile()) {
                        checkLink(cmd, relpath, dirent.name); } }); }); });
    }


    return {
        run: function () { traverseLinks(process.argv[2] || "create"); }
    };
}());

linker.run();
