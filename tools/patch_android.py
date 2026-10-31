#!/usr/bin/env python3
"""
Run after `flutter create --platforms=android .`
Turns the generated Flutter android/ project into the merged Utility Hub app:
  * includes the 4 native library modules from ../native
  * registers the Kotlin Compose plugin (same version as the template's Kotlin)
  * adds permissions / manifest attributes
  * installs a MainActivity that can launch the native screens
Works with both Groovy (.gradle) and Kotlin (.kts) templates.
"""
import os, re, sys, glob

A = "android"
MODULES = {"media_studio": "../native/media_studio",
           "alltools": "../native/alltools",
           "filemanager": "../native/filemanager",
           "apkstudio": "../native/apkstudio"}

def find(*names):
    for n in names:
        p = os.path.join(A, n)
        if os.path.exists(p):
            return p
    sys.exit("missing: " + " / ".join(names))

def rd(p): return open(p, encoding="utf-8").read()
def wr(p, s): open(p, "w", encoding="utf-8").write(s)

# ---------------------------------------------------------------- settings
sp = find("settings.gradle.kts", "settings.gradle")
s = rd(sp)
if "utilityhub-patched" not in s:
    lines = s.split("\n")
    out, kver = [], None
    for ln in lines:
        out.append(ln)
        if "org.jetbrains.kotlin.android" in ln and "version" in ln:
            m = re.search(r'version\s*\(?\s*"([0-9.]+)', ln)
            kver = m.group(1)
            out.append(ln.replace("org.jetbrains.kotlin.android",
                                  "org.jetbrains.kotlin.plugin.compose"))
        elif "com.android.application" in ln and "version" in ln:
            out.append(ln.replace("com.android.application", "com.android.library"))
    if not kver:
        sys.exit("Could not find Kotlin plugin version in " + sp)
    if int(kver.split(".")[0]) < 2:
        sys.exit("Kotlin %s is too old (need 2.x) - use a newer Flutter" % kver)
    s = "\n".join(out)
    s += "\n\n// utilityhub-patched\n"
    for name, path in MODULES.items():
        s += 'include(":%s")\nproject(":%s").projectDir = file("%s")\n' % (name, name, path)
    wr(sp, s)
    print("settings patched, Kotlin", kver)

# ---------------------------------------------------------------- app gradle
ap = find("app/build.gradle.kts", "app/build.gradle")
s = rd(ap)
if "utilityhub-patched" not in s:
    s += '''

// utilityhub-patched
android {
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(project(":media_studio"))
    implementation(project(":alltools"))
    implementation(project(":filemanager"))
    implementation(project(":apkstudio"))
}
'''
    wr(ap, s)
    ns = re.search(r'namespace\s*=?\s*"([^"]+)"', s).group(1)
    print("app patched, namespace", ns)
else:
    ns = re.search(r'namespace\s*=?\s*"([^"]+)"', s).group(1)

# ---------------------------------------------------------------- gradle.properties
gp = os.path.join(A, "gradle.properties")
g = rd(gp) if os.path.exists(gp) else ""
if "utilityhub" not in g:
    g += "\n# utilityhub\norg.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8\nandroid.suppressUnsupportedCompileSdk=36\n"
    wr(gp, g)

# ---------------------------------------------------------------- manifest
mp = os.path.join(A, "app/src/main/AndroidManifest.xml")
m = rd(mp)
if "utilityhub" not in m:
    perms = ["INTERNET", "REQUEST_INSTALL_PACKAGES"]
    add = "".join('    <uses-permission android:name="android.permission.%s"/>\n' % p
                  for p in perms if "android.permission.%s\"" % p not in m)
    m = m.replace("<application", add + "    <application", 1)
    m = re.sub(r'android:label="[^"]*"', 'android:label="Utility Hub"', m, count=1)
    m = m.replace("<application",
        '<application android:usesCleartextTraffic="true" '
        'android:requestLegacyExternalStorage="true"', 1)
    m = m.replace("<manifest", "<!-- utilityhub -->\n<manifest", 1) if False else m
    m += "\n<!-- utilityhub -->\n"
    wr(mp, m)
    print("manifest patched")

# ---------------------------------------------------------------- MainActivity
for f in glob.glob(os.path.join(A, "app/src/main/*/**/MainActivity.*"), recursive=True):
    os.remove(f)
d = os.path.join(A, "app/src/main/kotlin", *ns.split("."))
os.makedirs(d, exist_ok=True)
wr(os.path.join(d, "MainActivity.kt"), '''package %s

import android.content.ActivityNotFoundException
import android.content.Intent
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "utilityhub/launch")
            .setMethodCallHandler { call, result ->
                if (call.method != "open") { result.notImplemented(); return@setMethodCallHandler }
                val cls = when (call.arguments as? String) {
                    "media" -> "com.allmediastudio.app.MainActivity"
                    "tools" -> "com.example.alltools.MainActivity"
                    "files" -> "com.filemanager.app.MainActivity"
                    "studio" -> "com.apkstudio.app.MainActivity"
                    else -> null
                }
                if (cls == null) {
                    result.error("unknown", "Unknown menu", null)
                } else try {
                    startActivity(Intent().setClassName(packageName, cls))
                    result.success(null)
                } catch (e: ActivityNotFoundException) {
                    result.error("missing", e.message, null)
                }
            }
    }
}
''' % ns)
print("MainActivity written in", d)
